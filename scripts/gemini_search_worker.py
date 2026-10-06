#!/usr/bin/env python3
"""gemini_search_worker.py — Gemini Flash + Google Search grounding 하위 에이전트 SSOT.

Codex 하위에이전트·Devin·agy·Grok CLI가 공통으로 부르는 단일 스크립트.
native generateContent에 tools=[{"google_search":{}}]를 붙여 공식 문서·
최신 사양을 확인하고, 출처가 달린 <=3KB JSON 카드를 stdout으로 돌려준다.

Subcommands:
    search "<질문>" [--domains a.io,b.dev] [--depth L1|L2|L3]
                  [--max-chars 3000] [--brief-id id] [--model id]
                  [--brief-cap 10] [--allow-preview] [--state-dir dir]
                  [--card-file path] [--dry-run] [--timeout 45]
    models          해석된 모델 후보 목록만 출력(목록 조회는 1시간 캐시)

키는 GEMINI_API_KEY 환경변수만 읽고 x-goog-api-key 헤더로만 보낸다.
키 값은 stdout·stderr·예외·로그 어디에도 남기지 않는다.
재시도: 401/403/429 = 0회, 그 외 네트워크/5xx = 최대 1회.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

SCHEMA = "awx.gemini-search-worker.v1"
API_BASE = "https://generativelanguage.googleapis.com"
API_VER = "v1beta"
DEFAULT_MAX_CHARS = 3000
DEFAULT_BRIEF_CAP = 10
FREE_WARN_AT = 4000      # 월 호출 WARN 임계(지시서)
FREE_QUOTA_AT = 5000     # 월 무료 한도 추정 상한 — 초과도 경고만, 차단 안 함
CACHE_TTL_S = 24 * 3600
MODELS_CACHE_TTL_S = 3600
HTTP_TIMEOUT_S = 45
# 모델 id는 하드코딩하지 않는다(INV-G4). 해석 순서:
# --model > GEMINI_SEARCH_MODEL > /v1beta/models 최신 안정 Flash > 제품 SSOT.
PRODUCT_SSOT = "main/java/com/example/lms/learning/gemini/GeminiGateway.java"
PREVIEW_RE = re.compile(r"preview|experimental|exp|alpha|beta|nightly", re.I)
STABLE_FLASH_RE = re.compile(r"^gemini-(\d+)\.(\d+)-flash(?:-([a-z0-9]+))?$", re.I)
KEY_RE = re.compile(r"AIza[A-Za-z0-9_\-]{10,}")
SUCCESS_VERDICTS = {"GROUNDED_OK"}
VERDICTS = {"GROUNDED_OK", "NO_GROUNDING", "FINISH_TRUNCATED", "BLOCKED_SAFETY",
            "AUTH_FAIL", "RATE_LIMITED", "NET_FAIL",
            "KEY_MISSING", "MODEL_UNRESOLVED", "MODEL_PREVIEW_BLOCKED",
            "BRIEF_CAP_EXCEEDED", "EMPTY_RESPONSE", "DRY_RUN", "HTTP_ERROR"}
DEPTH = {
    # thinking 계열 Flash는 생각 토큰이 출력 상한을 함께 먹는다 — 여유 있게.
    "L1": (2048, "공식 출처 기반으로 5문장 이내로 답한다."),
    "L2": (4096, "공식 출처 우선, 적용 버전·날짜를 포함해 10문장 이내로 답한다."),
    "L3": (8192, "공식 출처 우선으로 교차검증하고 모호점·버전·확인 날짜를 명시해 15문장 이내로 답한다."),
}

KST = timezone(timedelta(hours=9))


def now_kst() -> str:
    return datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S KST")


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def scrub(text, key: str = "") -> str:
    """키 값·키 형태 문자열을 어떤 출력에도 남기지 않는다."""
    out = str(text)
    if key:
        out = out.replace(key, "<SECRET>")
    out = KEY_RE.sub("<SECRET>", out)
    out = re.sub(r"((?:x-goog-api-key|api[_-]?key|key)\s*[:=]\s*)[^\s&\"']+",
                 r"\1<SECRET>", out, flags=re.I)
    return out


def _script_root() -> Path:
    return Path(__file__).resolve().parent.parent


def _load_probe_synthesize():
    """web_search_probe_optimizer.synthesize 재사용(출처 랭킹·버전 플래그)."""
    try:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from web_search_probe_optimizer import synthesize  # noqa: E402
        return synthesize
    except Exception:
        return None


# --- http seam (테스트에서 patch 대상) ---------------------------------------

def _http_json(method: str, url: str, key: str, body: dict | None,
               timeout: int) -> tuple[int, dict]:
    data = None
    headers = {"Content-Type": "application/json"}
    if key:
        headers["x-goog-api-key"] = key
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            payload = {"error": {"message": scrub(e.reason or "http-error", key)}}
        return e.code, payload


# --- 모델 해석 ----------------------------------------------------------------

def _is_preview(model_id: str) -> bool:
    return bool(PREVIEW_RE.search(model_id))


def pick_stable_flash(models: list[dict], allow_preview: bool = False) -> str | None:
    """models.list 응답에서 최신 안정 gemini-*-flash id를 고른다."""
    best = None
    best_key = None
    for m in models or []:
        name = str(m.get("name") or "").split("/")[-1]
        methods = m.get("supportedGenerationMethods") or []
        if "generateContent" not in methods:
            continue
        if not allow_preview and _is_preview(name):
            continue
        mm = STABLE_FLASH_RE.match(name)
        if not mm:
            continue
        suffix = mm.group(3) or ""
        # 같은 버전이면 본계열 flash > -lite > 그 외 변형 순으로 선호.
        rank = (0 if suffix == "" else (1 if suffix == "lite" else 2))
        key = (int(mm.group(1)), int(mm.group(2)), -rank)
        if best_key is None or key > best_key:
            best_key = key
            best = name
    return best


def _models_cache_path(state_dir: Path) -> Path:
    return state_dir / "cache" / "models-list.json"


def list_models(key: str, state_dir: Path, timeout: int) -> tuple[list[dict] | None, str]:
    """/v1beta/models 목록(1시간 캐시). 실패 시 (None, 사유)."""
    cache = _models_cache_path(state_dir)
    try:
        if cache.is_file():
            saved = json.loads(cache.read_text(encoding="utf-8"))
            if utc_now().timestamp() - saved.get("fetchedEpoch", 0) < MODELS_CACHE_TTL_S:
                return saved.get("models") or [], "models-list-cache"
    except Exception:
        pass
    url = f"{API_BASE}/{API_VER}/models?pageSize=200"
    models: list[dict] = []
    for _ in range(3):
        code, payload = _http_json("GET", url, key, None, timeout)
        if code != 200:
            return None, f"models-list-http-{code}"
        models.extend(payload.get("models") or [])
        token = payload.get("nextPageToken")
        if not token:
            break
        url = f"{API_BASE}/{API_VER}/models?pageSize=200&pageToken={token}"
    try:
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps(
            {"fetchedEpoch": utc_now().timestamp(), "models": models},
            ensure_ascii=False), encoding="utf-8")
    except Exception:
        pass
    return models, "models-list"


def _product_ssot_model(root: Path) -> str | None:
    try:
        text = (root / PRODUCT_SSOT).read_text(encoding="utf-8", errors="replace")
        m = re.search(r'DEFAULT_MODEL\s*=\s*"([^"]+)"', text)
        return m.group(1) if m else None
    except Exception:
        return None


def resolve_model(args, key: str, state_dir: Path, root: Path) -> tuple[str | None, str]:
    """(model_id, source근거). 못 찾으면 (None, 사유)."""
    picked = args.model or os.environ.get("GEMINI_SEARCH_MODEL") or ""
    if picked:
        if _is_preview(picked) and not args.allow_preview:
            return None, "preview-id-requires---allow-preview"
        return picked, "flag-or-env"
    models, src = list_models(key, state_dir, args.timeout)
    if models:
        chosen = pick_stable_flash(models, args.allow_preview)
        if chosen:
            return chosen, src
    ssot = _product_ssot_model(root)
    if ssot:
        if _is_preview(ssot) and not args.allow_preview:
            return None, "product-ssot-preview-blocked"
        return ssot, "product-ssot:GeminiGateway.DEFAULT_MODEL"
    return None, "model-unresolved"


# --- 캐시 / 사용량 장부 ---------------------------------------------------------

def _cache_key(question: str, domains: list[str], depth: str, model: str) -> str:
    raw = json.dumps({"q": question, "d": domains, "p": depth, "m": model},
                     ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def cache_read(state_dir: Path, ckey: str) -> dict | None:
    path = state_dir / "cache" / f"{ckey}.json"
    try:
        if not path.is_file():
            return None
        saved = json.loads(path.read_text(encoding="utf-8"))
        if utc_now().timestamp() - saved.get("savedEpoch", 0) > CACHE_TTL_S:
            return None
        card = saved.get("card")
        # 성공 verdict 캐시만 재사용한다.
        if card and card.get("verdict") in SUCCESS_VERDICTS:
            return card
        return None
    except Exception:
        return None


def cache_write(state_dir: Path, ckey: str, card: dict) -> None:
    path = state_dir / "cache" / f"{ckey}.json"
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"savedEpoch": utc_now().timestamp(),
                                    "card": card}, ensure_ascii=False),
                        encoding="utf-8")
    except Exception:
        pass


def _usage_path(state_dir: Path, at: datetime | None = None) -> Path:
    at = at or utc_now()
    return state_dir / f"usage-{at.strftime('%Y%m')}.jsonl"


def usage_count_month(state_dir: Path) -> int:
    path = _usage_path(state_dir)
    try:
        return sum(1 for line in path.read_text(encoding="utf-8").splitlines()
                   if line.strip())
    except Exception:
        return 0


def usage_count_brief(state_dir: Path, brief_id: str) -> int:
    count = 0
    for path in state_dir.glob("usage-*.jsonl"):
        try:
            for line in path.read_text(encoding="utf-8").splitlines():
                try:
                    if json.loads(line).get("briefId") == brief_id:
                        count += 1
                except Exception:
                    continue
        except Exception:
            continue
    return count


def usage_append(state_dir: Path, rec: dict) -> int:
    path = _usage_path(state_dir)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        # 수작업으로 끝줄 개행이 빠진 장부에도 레코드가 합쳐지지 않게 한다.
        prefix = ""
        if path.exists() and path.stat().st_size > 0:
            with path.open("rb") as fh:
                fh.seek(-1, 2)
                if fh.read(1) != b"\n":
                    prefix = "\n"
        with path.open("a", encoding="utf-8") as fh:
            fh.write(prefix + json.dumps(rec, ensure_ascii=False) + "\n")
    except Exception:
        pass
    return usage_count_month(state_dir)


def spend_log(args, model: str, cache: str, http_status, error_class: str,
              usage: dict, verdict: str) -> None:
    rec = {
        "session": os.environ.get("AWX_AGENT_SESSION")
        or (f"agent-host:{os.environ.get('AWX_AGENT_HOST')}"
            if os.environ.get("AWX_AGENT_HOST") else "default"),
        "purpose": "gemini-search-worker",
        "provider": "gemini",
        "model": model,
        "tier": "free_tier_first",
        "why": args.brief_id or "search",
        "caller": "gemini_search_worker.py",
        "cache": cache,
        "httpStatus": http_status,
        "errorClass": error_class,
        "promptTokens": (usage or {}).get("promptTokenCount", 0),
        "completionTokens": (usage or {}).get("candidatesTokenCount", 0),
        "estCostClass": "free_quota_or_low_paid",
        "verdict": verdict,
    }
    print("[AWX][api-spend] " + json.dumps(rec, ensure_ascii=False),
          file=sys.stderr)


# --- 카드 조립 -----------------------------------------------------------------

def _card_base(verdict: str, model: str, model_source: str) -> dict:
    return {"schemaVersion": SCHEMA, "verdict": verdict,
            "model": model, "modelSource": model_source,
            "checkedAtKst": now_kst()}


def fit_card(card: dict, max_chars: int) -> dict:
    """JSON 카드를 max_chars 바이트 이하로 맞춘다(답 본문→출처 순으로 축소)."""
    def size(c):
        return len(json.dumps(c, ensure_ascii=False, indent=2).encode("utf-8"))
    if size(card) <= max_chars:
        return card
    card = dict(card)
    card["truncated"] = True
    ans = str(card.get("answer") or "")
    while ans and size(card) > max_chars:
        ans = ans[: max(0, len(ans) // 2)]
        card["answer"] = ans + ("…" if ans else "")
    srcs = list(card.get("sources") or [])
    while srcs and size(card) > max_chars:
        srcs = srcs[:-1]
        card["sources"] = srcs
    if size(card) > max_chars:
        card.pop("sourcesMd", None)
        card["answer"] = str(card.get("answer") or "")[:400]
    return card


def build_card(payload: dict, model: str, model_source: str,
               question: str, max_chars: int) -> dict:
    pf = payload.get("promptFeedback") or {}
    if pf.get("blockReason"):
        card = _card_base("BLOCKED_SAFETY", model, model_source)
        card["blockReason"] = scrub(pf.get("blockReason"))
        return fit_card(card, max_chars)
    cands = payload.get("candidates") or []
    if not cands:
        card = _card_base("EMPTY_RESPONSE", model, model_source)
        return fit_card(card, max_chars)
    cand = cands[0]
    finish = cand.get("finishReason") or ""
    gm = cand.get("groundingMetadata") or {}
    queries = [str(q)[:200] for q in (gm.get("webSearchQueries") or [])]
    chunks = gm.get("groundingChunks") or []
    raw_sources = []
    for i, ch in enumerate(chunks):
        web = ch.get("web") or {}
        uri = web.get("uri")
        if uri:
            raw_sources.append({"title": str(web.get("title") or uri)[:140],
                                "url": str(uri), "score": 100 - i,
                                "plate": "T1", "highlights": []})
    synth = _load_probe_synthesize()
    if synth:
        ranked = synth(raw_sources, None, max_chars).get("rankedItems") or []
        sources = [{"title": r.get("title"), "uri": r.get("url")}
                   for r in ranked if r.get("url")]
    else:
        seen, sources = set(), []
        for s in raw_sources:
            if s["url"] in seen:
                continue
            seen.add(s["url"])
            sources.append({"title": s["title"], "uri": s["url"]})
    parts = (cand.get("content") or {}).get("parts") or []
    answer = "".join(str(p.get("text") or "") for p in parts
                     if not p.get("thought")).strip()
    usage = payload.get("usageMetadata") or {}
    if finish == "SAFETY":
        verdict = "BLOCKED_SAFETY"
    elif finish == "MAX_TOKENS":
        verdict = "FINISH_TRUNCATED"
    elif sources or queries:
        verdict = "GROUNDED_OK"
    else:
        verdict = "NO_GROUNDING"
    card = _card_base(verdict, model, model_source)
    card.update({
        "answer": scrub(answer),
        "sources": sources[:8],
        "webSearchQueries": queries[:8],
        "finishReason": finish or None,
        "usage": {k: usage.get(k) for k in
                  ("promptTokenCount", "candidatesTokenCount", "totalTokenCount")
                  if usage.get(k) is not None},
        "requestId": payload.get("responseId"),
    })
    return fit_card(card, max_chars)


# --- search 실행 -----------------------------------------------------------------

def build_prompt(question: str, domains: list[str], depth: str) -> str:
    lines = [
        "당신은 공식 문서 확인용 검색 하위 에이전트다. Google 검색 grounding으로 "
        "공식 문서·공식 저장소·릴리스 노트·API 사양만 확인해 답한다.",
        "규칙: 출처 없는 주장 금지. 블로그보다 공식 문서 우선. 적용 버전과 확인 "
        "날짜를 명시한다.",
        f"질문: {question}",
    ]
    if domains:
        lines.append("우선 도메인: " + ", ".join(domains))
    lines.append("깊이: " + DEPTH[depth][1])
    return "\n".join(lines)


def run_search(args, key: str, state_dir: Path, root: Path) -> tuple[dict, int]:
    model, model_source = resolve_model(args, key, state_dir, root)
    if model is None:
        bad = "MODEL_PREVIEW_BLOCKED" if "preview" in model_source else "MODEL_UNRESOLVED"
        card = _card_base(bad, "", "")
        card["detail"] = scrub(model_source)
        return fit_card(card, args.max_chars), 2
    domains = [d.strip() for d in (args.domains or "").split(",") if d.strip()]
    if args.dry_run:
        card = _card_base("DRY_RUN", model, model_source)
        card["request"] = {
            "url": f"{API_BASE}/{API_VER}/models/{model}:generateContent",
            "tools": [{"google_search": {}}],
            "depth": args.depth, "domains": domains,
            "maxOutputTokens": DEPTH[args.depth][0],
        }
        return fit_card(card, args.max_chars), 0
    brief = args.brief_id or ""
    if brief and usage_count_brief(state_dir, brief) >= args.brief_cap:
        card = _card_base("BRIEF_CAP_EXCEEDED", model, model_source)
        card["briefId"] = brief
        card["briefCap"] = args.brief_cap
        return fit_card(card, args.max_chars), 2
    ckey = _cache_key(args.text, domains, args.depth, model)
    cached = cache_read(state_dir, ckey)
    if cached is not None:
        cached = dict(cached)
        cached["cacheHit"] = True
        cached["callsUsed"] = 0
        spend_log(args, model, "hit", None, "", cached.get("usage"), cached.get("verdict", ""))
        return fit_card(cached, args.max_chars), 0

    url = f"{API_BASE}/{API_VER}/models/{model}:generateContent"
    body = {
        "contents": [{"parts": [{"text": build_prompt(args.text, domains, args.depth)}]}],
        "tools": [{"google_search": {}}],
        "generationConfig": {"temperature": 0.2,
                             "maxOutputTokens": DEPTH[args.depth][0]},
    }
    http_status, payload, attempts = None, {}, 0
    verdict = "NET_FAIL"
    for attempt in range(2):
        attempts = attempt + 1
        try:
            http_status, payload = _http_json("POST", url, key, body, args.timeout)
        except Exception as e:  # URLError/timeout 등 전송 계열 — 최대 1회 재시도
            verdict = "NET_FAIL"
            payload = {"error": {"message": scrub(e, key)}}
            http_status = None
            if attempt == 0:
                continue
            break
        if http_status in (401, 403):
            verdict = "AUTH_FAIL"
            break  # 재시도 금지
        if http_status == 429:
            verdict = "RATE_LIMITED"
            break  # 재시도 금지
        if http_status == 200:
            break
        verdict = "HTTP_ERROR"
        if http_status and 500 <= http_status < 600 and attempt == 0:
            continue  # 5xx만 1회 재시도
        break

    if http_status == 200:
        card = build_card(payload, model, model_source, args.text, args.max_chars)
        verdict = card["verdict"]
    else:
        card = _card_base(verdict, model, model_source)
        err = (payload.get("error") or {})
        card["httpStatus"] = http_status
        card["errorMessage"] = scrub(err.get("message") or "", key)[:240]
        card = fit_card(card, args.max_chars)
    card["cacheHit"] = False
    card["callsUsed"] = attempts

    month = usage_append(state_dir, {
        "ts": utc_now().isoformat(timespec="seconds"),
        "briefId": brief, "model": model, "verdict": verdict,
        "webQueries": len(card.get("webSearchQueries") or []),
        "q": (args.text or "")[:80]})
    if month >= FREE_QUOTA_AT:
        card["quotaWarn"] = "OVER_FREE_QUOTA_ALLOWED"
    elif month >= FREE_WARN_AT:
        card["quotaWarn"] = "WARN_NEAR_FREE_QUOTA"
    card["monthCalls"] = month
    spend_log(args, model, "miss", http_status,
              verdict if verdict != "GROUNDED_OK" else "",
              card.get("usage"), verdict)
    # 성공 verdict만 캐시 — 잘린/무근거 응답이 24시간 캐시를 오염시키지 않도록.
    if verdict in SUCCESS_VERDICTS:
        cache_write(state_dir, ckey, card)
    return fit_card(card, args.max_chars), 0


# --- cli -----------------------------------------------------------------------

def _add_state_args(p):
    p.add_argument("--state-dir", default=None,
                   help="캐시·사용량 장부 루트 (기본 <root>/var/gemini-search-worker)")
    p.add_argument("--timeout", type=int, default=HTTP_TIMEOUT_S)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="gemini_search_worker")
    sub = ap.add_subparsers(dest="action")
    p = sub.add_parser("search")
    p.add_argument("text", nargs="?", default=None)
    p.add_argument("--domains", default="")
    p.add_argument("--depth", choices=sorted(DEPTH), default="L1")
    p.add_argument("--max-chars", type=int, default=DEFAULT_MAX_CHARS)
    p.add_argument("--brief-id", default="")
    p.add_argument("--brief-cap", type=int, default=DEFAULT_BRIEF_CAP)
    p.add_argument("--model", default="")
    p.add_argument("--allow-preview", action="store_true")
    p.add_argument("--card-file", default=None)
    p.add_argument("--dry-run", action="store_true")
    _add_state_args(p)
    m = sub.add_parser("models")
    m.add_argument("--allow-preview", action="store_true")
    _add_state_args(m)

    args = ap.parse_args(argv)
    if not args.action:
        ap.print_help()
        return 2
    root = _script_root()
    state_dir = Path(args.state_dir) if args.state_dir else root / "var" / "gemini-search-worker"
    key = os.environ.get("GEMINI_API_KEY") or ""

    if args.action == "models":
        if not key:
            print(json.dumps({"schemaVersion": SCHEMA, "verdict": "KEY_MISSING",
                              "detail": "env GEMINI_API_KEY not set"},
                             ensure_ascii=False))
            return 2
        models, src = list_models(key, state_dir, args.timeout)
        if models is None:
            print(json.dumps({"schemaVersion": SCHEMA, "verdict": "NET_FAIL",
                              "detail": scrub(src, key)}, ensure_ascii=False))
            return 2
        ids = [str(x.get("name") or "").split("/")[-1] for x in models]
        stable = [i for i in ids if STABLE_FLASH_RE.match(i)
                  and (args.allow_preview or not _is_preview(i))]
        print(json.dumps({"schemaVersion": SCHEMA, "source": src,
                          "picked": pick_stable_flash(models, args.allow_preview),
                          "stableFlash": sorted(stable),
                          "total": len(ids)}, ensure_ascii=False, indent=2))
        return 0

    if not (args.text or "").strip():
        print(json.dumps({"schemaVersion": SCHEMA, "verdict": "USAGE",
                          "detail": "empty question"}, ensure_ascii=False))
        return 2
    if not key:
        print(json.dumps(_card_base("KEY_MISSING", "", ""), ensure_ascii=False))
        return 2
    card, code = run_search(args, key, state_dir, root)
    out = json.dumps(card, ensure_ascii=False, indent=2)
    print(out)
    if args.card_file:
        try:
            Path(args.card_file).write_text(out + "\n", encoding="utf-8")
        except Exception as e:
            print(scrub(f"card-file-write-failed: {e}", key), file=sys.stderr)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
