#!/usr/bin/env python3
"""AWX agy 웹서치 결과 융합기 — Weighted-RRF + 권위 등급 + 게이트.

Contract: DEMO1-DEVIN-AGY-UAW-WEB-OPTIMIZE-20261002.
UAW.txt 설계 비유의 결정적 부분만 구현 (P4 융합, P5 신선도, P6 본문확인,
P7 중복·다양성, P9 게이트/힌트, P12 브레드크럼). 표준 라이브러리만 사용,
네트워크 0, 파일 쓰기 0.

stdin 또는 --in 파일:
  {"question":"…","plate":"W3_TECH",
   "items":[{"url","title","date"?,"snippet","query_id","rank","body_checked"?}]}

stdout JSON:
  {"ranked":[{url,tier,score,stale,dup_group,body_checked,title,date}],
   "cite":[... 상위 N 대표 ...],
   "gate":{"pass":bool,"flags":[...],"next_query_hints":[...]},
   "breadcrumb":"웹: <plate> · 검색 n회 · 본문확인 n · 출처 n(T1 n) · 모순 …"}
--format md 로 사람이 읽는 표를 낸다. --top N(기본 5).
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
import unicodedata
from datetime import date, datetime, timezone
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

RRF_K = 60
DUP_JACCARD = 0.6
STALE_DAYS = 365
BODY_BOOST = 1.25
FRESH_PLATES = {"W2_FRESH", "W3_TECH"}
MIN_CITE = {"W9_LITE": 1, "WB_BRAVE": 3}  # 나머지 플레이트 기본 2
TOP_N = 5

TRACK_PARAMS = re.compile(
    r"^(utm_|fbclid|gclid|gad_|mc_|igshid|ref$|ref_|spm|si$|feature$|_ga)",
    re.IGNORECASE)
YEAR_RE = re.compile(r"\b(19|20)\d{2}\b")
NUM_RE = re.compile(r"\b\d+(?:\.\d+)?\b")

PLATES = ("W1_AUTH", "W2_FRESH", "W3_TECH", "W4_LOCAL", "W9_LITE", "WB_BRAVE")

HINTS = {
    "SPARSE": [
        "하위 질문을 다른 표현(영어·동의어)으로 1~2개 더 검색",
        "\"<핵심 앵커>\" 정확 일치 검색 1회",
    ],
    "LOW_AUTHORITY": [
        "site:<해당 주제 T1 공식 도메인> + 핵심 앵커",
        "공식 도움말·changelog·release notes 위주로 재검색",
    ],
    "CONFLICT_SUSPECT": [
        "각 출처의 날짜·버전·수치를 나란히 대조 ('A는 X, B는 Y'로 분리)",
        "site:<공식 도메인> 최신 문서로 어느 쪽이 맞는지 확인",
    ],
}


def normalize_url(url: str) -> str:
    try:
        parts = urlsplit(str(url).strip())
    except ValueError:
        return str(url).strip().lower()
    host = (parts.hostname or "").lower()
    if host.startswith("www."):
        host = host[4:]
    if parts.port and parts.port not in (80, 443):
        host = "%s:%d" % (host, parts.port)
    query = urlencode([(k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True)
                       if not TRACK_PARAMS.match(k)])
    path = parts.path or "/"
    if path != "/" and path.endswith("/"):
        path = path[:-1]
    return urlunsplit(("", host, path, query, ""))


def domain_of(url: str) -> str:
    try:
        host = (urlsplit(url).hostname or "").lower()
    except ValueError:
        return ""
    return host[4:] if host.startswith("www.") else host


def domain_family(url: str) -> str:
    """등록 도메인(마지막 2레이블) — 같은 회사 서브도메인은 한 출처로 본다."""
    host = domain_of(url)
    labels = [p for p in host.split(".") if p]
    if len(labels) >= 3 and labels[-2] in ("co", "or", "go", "ac", "ne", "com") \
            and labels[-1] in ("kr", "jp", "uk"):
        return ".".join(labels[-3:])
    return ".".join(labels[-2:]) if len(labels) >= 2 else host


def load_tiers(root: Path) -> tuple[dict, dict]:
    path = root / "references" / "agy-web" / "authority_tiers.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        tiers = data.get("tiers", {})
        weights = data.get("weights", {})
        domain_tier = {}
        for tier, domains in tiers.items():
            for d in domains:
                domain_tier[d.lower()] = tier
        return domain_tier, {k: float(v) for k, v in weights.items()}
    except (OSError, ValueError, KeyError):
        return {}, {"T1": 1.0, "T2": 0.8, "T3": 0.6, "T4": 0.3}


def tier_of(url: str, domain_tier: dict, weights: dict) -> tuple[str, float]:
    host = domain_of(url)
    best, found = "", "T4"
    for d, tier in domain_tier.items():
        if host == d or host.endswith("." + d):
            if len(d) > len(best) or (len(d) == len(best) and tier < found):
                best, found = d, tier
    return found, weights.get(found, 0.3)


def parse_date(value) -> date | None:
    if not value:
        return None
    text = str(value).strip()[:10]
    for fmt in ("%Y-%m-%d", "%Y-%m", "%Y"):
        try:
            dt = datetime.strptime(text, fmt)
            return dt.date()
        except ValueError:
            continue
    return None


def tokens(text: str) -> set:
    text = unicodedata.normalize("NFKC", text.lower())
    words = re.findall(r"[0-9a-zA-Z가-힣]+", text)
    grams = set(words)
    for w in words:
        if len(w) >= 3:
            grams.update(w[i:i + 3] for i in range(len(w) - 2))
    return grams


def jaccard(a: set, b: set) -> float:
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def conflict_suspect(items) -> bool:
    """같은 query_id 안에서 연도·수치가 서로 다른 요약이 섞이면 의심."""
    groups = {}
    for it in items:
        groups.setdefault(str(it.get("query_id", "")), []).append(it)
    for group in groups.values():
        years = set()
        nums = []
        for it in group:
            snippet = str(it.get("snippet") or "") + " " + str(it.get("title") or "")
            years.update(YEAR_RE.findall(snippet))
            nums.append(set(NUM_RE.findall(snippet)))
        if len({y for y in years}) > 1:
            return True
        present = [n for n in nums if n]
        if len(present) >= 2:
            union = set().union(*present)
            shared = present[0]
            for n in present[1:]:
                shared = shared & n
            # 공통 수치가 하나도 없고 각 요약이 서로 다른 수치를 주장하면 모순 의심
            if not shared and union:
                return True
    return False


def fuse(payload: dict, root: Path, top_n: int = TOP_N,
         today: date | None = None) -> dict:
    today = today or datetime.now(timezone.utc).date()
    plate = str(payload.get("plate") or "W4_LOCAL").upper()
    if plate not in PLATES:
        plate = "W4_LOCAL"
    items = payload.get("items") or []
    domain_tier, weights = load_tiers(root)

    # 1) URL 정규화 + 같은 URL 합치기(가장 좋은 rank 유지)
    merged = {}
    for it in items:
        if not isinstance(it, dict):
            continue
        key = normalize_url(str(it.get("url") or ""))
        if not key:
            continue
        rank = it.get("rank")
        try:
            rank = int(rank)
        except (TypeError, ValueError):
            rank = 99
        qid = str(it.get("query_id") or "")
        if key in merged:
            m = merged[key]
            m["rank"] = min(m["rank"], rank)
            m["query_ids"].add(qid)
            for f in ("title", "snippet", "date"):
                if not m.get(f) and it.get(f):
                    m[f] = it.get(f)
            m["body_checked"] = bool(m.get("body_checked") or it.get("body_checked"))
        else:
            merged[key] = {
                "url": str(it.get("url") or ""), "norm": key,
                "title": str(it.get("title") or ""),
                "snippet": str(it.get("snippet") or ""),
                "date": it.get("date"), "rank": rank,
                "query_ids": {qid}, "body_checked": bool(it.get("body_checked")),
            }
    rows = list(merged.values())

    # 2) 복제 그룹 (제목+요약 3-gram 자카드 ≥0.6, union-find)
    # 역색인으로 후보 쌍만 비교 — 1000건도 1초 이내
    tok = [tokens(r["title"] + " " + r["snippet"]) for r in rows]
    words = [set(re.findall(r"[0-9a-zA-Z가-힣]+",
                          unicodedata.normalize("NFKC", (r["title"] + " " + r["snippet"]).lower())))
             for r in rows]
    df = {}
    for wset in words:
        for w in wset:
            df[w] = df.get(w, 0) + 1
    df_cap = max(20, int(len(rows) * 0.05))
    index = {}
    for i, wset in enumerate(words):
        for w in wset:
            if df[w] <= df_cap:
                index.setdefault(w, []).append(i)
    parent = list(range(len(rows)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    seen = set()
    for ids in index.values():
        for a in range(len(ids)):
            for b in range(a + 1, len(ids)):
                i, j = ids[a], ids[b]
                if (i, j) in seen:
                    continue
                seen.add((i, j))
                if jaccard(tok[i], tok[j]) >= DUP_JACCARD:
                    pi, pj = find(i), find(j)
                    if pi != pj:
                        parent[pj] = pi
    groups = {}
    for i in range(len(rows)):
        groups.setdefault(find(i), []).append(i)

    # 3) RRF(k=60) × 권위 × 신선도 × 본문확인
    for r in rows:
        # RRF(k=60): 여러 질의에서 겹친 URL일수록 가산 (query_ids 개수만큼 누적)
        rrf = len(r["query_ids"]) / (RRF_K + r["rank"]) if r["query_ids"] else 0.0
        tier, w = tier_of(r["url"], domain_tier, weights)
        score = rrf * w
        d = parse_date(r["date"])
        stale = False
        if plate in FRESH_PLATES:
            if d is not None:
                age = (today - d).days
                if age > STALE_DAYS:
                    stale = True
                    score *= 0.5
                else:
                    score *= 1.0 + 0.5 * max(0.0, (STALE_DAYS - age) / STALE_DAYS)
        if r["body_checked"]:
            score *= BODY_BOOST
        r.update(tier=tier, score=score, stale=stale, age_days=(today - d).days if d else None)

    # 4) 그룹 대표 선정(점수 최고) → 정렬
    for members in groups.values():
        rep = max(members, key=lambda i: rows[i]["score"])
        for i in members:
            rows[i]["dup_rep"] = i == rep
            rows[i]["dup_group"] = find(i)
    ranked = sorted((r for r in rows if r["dup_rep"]), key=lambda r: -r["score"])

    # 5) 게이트
    cite = ranked[:top_n]
    n_cite = len(cite)
    need = MIN_CITE.get(plate, 2)
    t1 = sum(1 for r in cite if r["tier"] == "T1")
    families = {domain_family(r["url"]) for r in cite}
    flags = []
    if n_cite < need:
        flags.append("SPARSE")
    if t1 == 0 and not any(r["tier"] in ("T1", "T2") for r in cite) \
            and len(families) < 2:
        flags.append("LOW_AUTHORITY")
    if conflict_suspect(items):
        flags.append("CONFLICT_SUSPECT")
    passed = not ({"SPARSE", "LOW_AUTHORITY"} & set(flags))
    hints = []
    for f in flags:
        hints.extend(HINTS.get(f, []))

    body_n = sum(1 for r in ranked if r["body_checked"])
    n_queries = len({str(it.get("query_id") or "") for it in items if isinstance(it, dict)})
    breadcrumb = "웹: %s · 검색 %d회 · 본문확인 %d · 출처 %d(T1 %d) · 모순 %s" % (
        plate, n_queries, body_n, n_cite, t1,
        "있음" if "CONFLICT_SUSPECT" in flags else "없음")

    def view(r):
        return {"url": r["url"], "tier": r["tier"], "score": round(r["score"], 4),
                "stale": r["stale"], "dup_group": r["dup_group"],
                "body_checked": r["body_checked"], "title": r["title"],
                "date": r["date"], "queries": sorted(r["query_ids"])}

    return {
        "question": payload.get("question", ""), "plate": plate,
        "ranked": [view(r) for r in ranked],
        "cite": [view(r) for r in cite],
        "gate": {"pass": passed, "flags": flags, "next_query_hints": hints,
                 "min_cite": need},
        "breadcrumb": breadcrumb,
    }


def render_md(result: dict) -> str:
    lines = ["| # | tier | score | stale | body | url |", "|---|---|---|---|---|---|"]
    for i, r in enumerate(result["ranked"], 1):
        lines.append("| %d | %s | %.4f | %s | %s | %s |" % (
            i, r["tier"], r["score"], "STALE" if r["stale"] else "",
            "Y" if r["body_checked"] else "", r["url"]))
    g = result["gate"]
    lines.append("")
    lines.append("gate: pass=%s flags=%s" % (g["pass"], ",".join(g["flags"]) or "-"))
    for h in g["next_query_hints"]:
        lines.append("- hint: %s" % h)
    lines.append("")
    lines.append(result["breadcrumb"])
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--in", dest="infile", type=Path)
    ap.add_argument("--top", type=int, default=TOP_N)
    ap.add_argument("--format", choices=("json", "md"), default="json")
    ap.add_argument("--root", type=Path,
                    default=Path(__file__).resolve().parent.parent)
    args = ap.parse_args()
    try:
        raw = args.infile.read_bytes() if args.infile else sys.stdin.buffer.read()
        payload = json.loads(raw.decode("utf-8", errors="replace"))
        if not isinstance(payload, dict):
            raise ValueError("payload-not-object")
        result = fuse(payload, args.root, args.top)
    except Exception as exc:  # fail-soft: 빈 결과라도 낸다
        result = {"ranked": [], "cite": [],
                  "gate": {"pass": False, "flags": ["INPUT_ERROR"],
                           "next_query_hints": ["입력 JSON 형식 확인: " + str(exc)[:80]],
                           "min_cite": 0},
                  "breadcrumb": "웹: - · 검색 0회 · 본문확인 0 · 출처 0(T1 0) · 모순 없음",
                  "plate": "-", "question": ""}
    if args.format == "md":
        sys.stdout.buffer.write((render_md(result) + "\n").encode("utf-8"))
    else:
        sys.stdout.buffer.write(
            json.dumps(result, ensure_ascii=False, indent=2).encode("utf-8") + b"\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
