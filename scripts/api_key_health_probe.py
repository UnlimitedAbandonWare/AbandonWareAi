#!/usr/bin/env python3
# [AWX][api-health] API key health probe for demo-1 providers.
# Contract: DEMO1-DEVIN-API-KEY-HEALTH-PROBE-20260929.
#
# DEPRECATED (2026-09-29, absorbed by DEMO1-DEVIN-LITE-API-TESTKIT-20260929):
#   use `python -m scripts.apikit check` / `scripts\apikit.ps1 check` — same
#   0-won L0 surface, canonical failure classes (docs/API_ROUTING_SPEC.md
#   §External API failure classification) including PLAN_GATE. Kept for
#   compatibility; do not extend.
#
# Answers one question per provider: which key fails and WHY
# (expired/invalid/permission/balance/rate-limit/network/model-missing).
#
# Cost contract:
#   default run = L0 only = 0 KRW (documented free introspection/list endpoints).
#   --paid enables L1 = exactly one minimal generation/search call per provider,
#   1 output token, no retries. AWX_AGENT_SPEND_GUARD rules apply.
#   L0-absent providers are marked "none" and are never called unless --paid.
#
# Safety contract:
#   key values are never printed/written; only len + sha256[:8] + source match.
#   redirects are NOT followed (Authorization must not leak to another host).
#   10s timeout, no retries, providers probed in parallel.
#   on 401/403 the remaining steps for that provider are skipped.
#
# Key-name inventory: configs/api-routing.yaml, docs/API_ROUTING_SPEC.md,
#   main/resources/application*.yml, .secrets/providers.json (names only).
#
# Output: console table + data/agent-handoff/api-health/api-health-<ts>.json
# Exit: 0 = all probed providers OK, 3 = any failure, 2 = script error.

import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = Path(__file__).resolve().parent.parent
OUTDIR = ROOT / "data" / "agent-handoff" / "api-health"
SECRETS = ROOT / ".secrets" / "providers.json"
ROUTING = ROOT / "configs" / "api-routing.yaml"
TIMEOUT_S = 10.0
MAX_BODY = 1048576  # 1 MiB — vendor model lists can exceed 200 KB

CLS_OK = "OK"
CLS_INVALID = "KEY_INVALID_OR_EXPIRED"
CLS_MISSING = "KEY_MISSING"
CLS_MISMATCH = "KEY_MISMATCH"
CLS_FORBIDDEN = "FORBIDDEN_REGION_OR_IP"
CLS_QUOTA = "QUOTA_OR_BALANCE"
CLS_RATE = "RATE_LIMIT"
CLS_MODEL = "MODEL_NOT_FOUND"
CLS_NETWORK = "NETWORK"
CLS_UNKNOWN = "UNKNOWN"

FIX = {
    "jev": "https://vercel.com/d?to=%2F%5Bteam%5D%2F%7E%2Fai-gateway%2Fapi-keys",
    "openai": "https://platform.openai.com/api-keys",
    "gemini": "https://aistudio.google.com/apikey",
    "groq": "https://console.groq.com/keys",
    "anthropic": "https://console.anthropic.com/settings/keys",
    "mistral": "https://console.mistral.ai/api-keys",
    "openrouter": "https://openrouter.ai/settings/keys",
    "zai": "https://z.ai/manage-apikey/apikey-list",
    "opencode": "https://opencode.ai/zen",
    "soniox": "https://console.soniox.com",
    "deepgram": "https://console.deepgram.com",
    "pinecone": "https://app.pinecone.io",
    "upstash_vector": "https://console.upstash.com",
    "upstash_redis": "https://console.upstash.com",
    "serpapi": "https://serpapi.com/manage-api-key",
    "naver": "https://developers.naver.com/apps",
    "kakao": "https://developers.kakao.com",
    "brave": "https://api-dashboard.search.brave.com/app/keys",
    "tavily": "https://app.tavily.com",
    "ollama": "ollama serve / ollama ls (local)",
    "devin": "https://app.devin.ai/settings",
}


# ---------------------------------------------------------------- HTTP layer

class Resp:
    __slots__ = ("status", "headers", "body", "latency_ms", "error", "truncated")

    def __init__(self, status=None, headers=None, body=b"", latency_ms=0,
                 error=None, truncated=False):
        self.status = status
        self.headers = headers or {}
        self.body = body or b""
        self.latency_ms = latency_ms
        self.error = error
        self.truncated = truncated

    def json(self):
        try:
            return json.loads(self.body.decode("utf-8", errors="replace"))
        except ValueError:
            return None


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # never follow; Authorization must not leak to another host


_OPENER = urllib.request.build_opener(_NoRedirect)


def http_request(method, url, headers=None, body=None, timeout=TIMEOUT_S):
    """One bounded HTTP call. Never follows redirects. Returns Resp."""
    import time
    data = json.dumps(body).encode("utf-8") if isinstance(body, (dict, list)) else body
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    t0 = time.monotonic()
    try:
        with _OPENER.open(req, timeout=timeout) as r:
            raw = r.read(MAX_BODY + 1)
            return Resp(r.status, dict(r.headers), raw[:MAX_BODY],
                        int((time.monotonic() - t0) * 1000),
                        truncated=len(raw) > MAX_BODY)
    except urllib.error.HTTPError as e:
        try:
            raw = e.read(MAX_BODY + 1)
        except Exception:
            raw = b""
        return Resp(e.code, dict(e.headers or {}), raw[:MAX_BODY],
                    int((time.monotonic() - t0) * 1000),
                    truncated=len(raw) > MAX_BODY)
    except (urllib.error.URLError, OSError, ValueError) as e:
        return Resp(None, {}, b"", int((time.monotonic() - t0) * 1000),
                    "%s: %s" % (type(e).__name__, str(getattr(e, "reason", e))[:160]))


# --------------------------------------------------------- key source reading

def _winreg_env(name):
    """User/Machine env via registry (process env misses non-exported User vars)."""
    out = {}
    if os.name != "nt":
        return out
    import winreg
    for scope, hive, sub in (
            ("user", winreg.HKEY_CURRENT_USER, "Environment"),
            ("machine", winreg.HKEY_LOCAL_MACHINE,
             r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment")):
        try:
            with winreg.OpenKey(hive, sub) as k:
                v, _ = winreg.QueryValueEx(k, name)
                if isinstance(v, str) and v.strip():
                    out[scope] = v.strip()
        except OSError:
            pass
    return out


_SECRETS_CACHE = {"loaded": False, "values": {}}


def _secrets_values():
    if not _SECRETS_CACHE["loaded"]:
        _SECRETS_CACHE["loaded"] = True
        try:
            data = json.loads(SECRETS.read_text(encoding="utf-8"))
            vals = data.get("values") or {}
            _SECRETS_CACHE["values"] = {
                n: (e.get("value") if isinstance(e, dict) else e)
                for n, e in vals.items()}
        except (OSError, ValueError):
            _SECRETS_CACHE["values"] = {}
    return _SECRETS_CACHE["values"]


def sha8(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:8]


def key_sources(name):
    """{source: value} for one env name. Values never leave this function's callers' hashing."""
    src = {}
    v = os.environ.get(name)
    if v and v.strip():
        src["process"] = v.strip()
    src.update(_winreg_env(name))
    sv = _secrets_values().get(name)
    if isinstance(sv, str) and sv.strip():
        src["secrets"] = sv.strip()
    return src


def resolve_key(env_names):
    """Pick the first env name with any value; resolution order process>user>secrets>machine."""
    for name in env_names:
        src = key_sources(name)
        if not src:
            continue
        val = next((src[s] for s in ("process", "user", "secrets", "machine") if s in src), None)
        return name, src, val
    return (env_names[0] if env_names else ""), {}, None


def source_match(src):
    hashes = {s: sha8(v) for s, v in src.items()}
    uniq = set(hashes.values())
    if len(src) == 0:
        return "absent", hashes
    if len(uniq) == 1:
        return ("single:" + next(iter(src)) if len(src) == 1 else "match"), hashes
    return "MISMATCH", hashes


def merged_key_row(provider, label, parts):
    """Composite key (id+secret / url+token): per-name match — never compare
    across different env names (their values legitimately differ)."""
    per_name, sha_map, sources, primary = [], {}, [], None
    any_missing = False
    for name, src, val in parts:
        m, h = source_match(src)
        per_name.append("%s:%s" % (name, m))
        sha_map[name] = h
        for s in src:
            if s not in sources:
                sources.append(s)
        if val is None:
            any_missing = True
        elif primary is None:
            primary = val
    overall = "MISMATCH" if any(p.endswith(":MISMATCH") for p in per_name) else \
        ("absent" if not sources else "match")
    row = {"provider": provider, "key_env": label,
           "key_present": not any_missing, "key_len": len(primary or ""),
           "key_sha8": sha8(primary) if primary else None,
           "key_sources": sources, "source_match": overall,
           "source_sha8": sha_map, "match_detail": "; ".join(per_name),
           "l0_status": "none",
           "classification": CLS_MISSING if any_missing else None,
           "detail": "key pair/triple incomplete: %s" % "; ".join(per_name)
           if any_missing else "",
           "fix": FIX.get(provider, ""), "latency_ms": None, "l1": None}
    return row


# ---------------------------------------------------------------- classifiers

def _err_fields(resp):
    """Pull common vendor error fields out of a JSON body."""
    j = resp.json() or {}
    err = j.get("error") if isinstance(j.get("error"), dict) else {}
    code = err.get("code") or j.get("error_type") or j.get("errorCode") or err.get("type")
    status = err.get("status")
    reasons = []
    for d in err.get("details") or []:
        if isinstance(d, dict) and d.get("reason"):
            reasons.append(str(d["reason"]))
    msg = (err.get("message") or j.get("message") or j.get("error_message")
           or j.get("errorMessage") or "")
    return str(code or status or ""), [str(r) for r in reasons], str(msg)


def classify_generic(resp, balance_hint=None):
    """Default status->classification map shared by OpenAI-compat style APIs."""
    if resp.error is not None:
        return CLS_NETWORK, resp.error
    s = resp.status
    if s == 200:
        return CLS_OK, "ok"
    code, reasons, msg = _err_fields(resp)
    blob = " ".join([code, " ".join(reasons), msg]).lower()
    if s == 401:
        return CLS_INVALID, code or "401 unauthorized"
    if s == 402 or "insufficient_quota" in blob or ("balance" in blob and s == 403):
        return CLS_QUOTA, code or "payment/quota"
    if s == 403:
        if any(r in ("PERMISSION_DENIED", "USER_LOCATION_DENIED") for r in reasons) or \
                "location" in blob or "region" in blob or "country" in blob:
            return CLS_FORBIDDEN, code or "403 forbidden"
        return CLS_FORBIDDEN, code or "403 forbidden"
    if s == 404:
        return CLS_MODEL, code or "404 not found"
    if s == 429:
        if "insufficient_quota" in blob or "quota" in blob and "rate" not in blob:
            return CLS_QUOTA, code or "quota exceeded"
        return CLS_RATE, code or "429 rate limited"
    if s in (301, 302, 303, 307, 308):
        return CLS_UNKNOWN, "redirect %s not followed" % s
    return CLS_UNKNOWN, "http %s %s" % (s, code)


def classify_jev(resp):
    if resp.error is not None:
        return CLS_NETWORK, resp.error
    code, reasons, msg = _err_fields(resp)
    if resp.status == 200:
        j = resp.json() or {}
        bal = j.get("balance") if isinstance(j, dict) else None
        if isinstance(bal, (int, float)) and bal <= 0.01:
            return CLS_QUOTA, "credits balance ~0"
        return CLS_OK, "credits ok%s" % ("" if bal is None else " balance=%s" % bal)
    if resp.status == 401 and "authentication failed" in msg.lower():
        return CLS_INVALID, "Authentication failed — expired/revoked/other-team key likely"
    return classify_generic(resp)


def classify_gemini(resp):
    if resp.error is not None:
        return CLS_NETWORK, resp.error
    code, reasons, msg = _err_fields(resp)
    blob = " ".join([code] + reasons).upper()
    if resp.status == 200:
        return CLS_OK, "ok"
    if "API_KEY_INVALID" in blob or "API_KEY_EXPIRED" in blob or "API_KEY_LEAKED" in blob \
            or "key" in msg.lower() and resp.status in (400, 401, 403) and "INVALID" in blob:
        return CLS_INVALID, code or reasons[0] if reasons else "api key rejected"
    if "RESOURCE_EXHAUSTED" in blob:
        return CLS_QUOTA, code or "RESOURCE_EXHAUSTED"
    return classify_generic(resp)


def classify_soniox(resp):
    if resp.error is not None:
        return CLS_NETWORK, resp.error
    code, _, msg = _err_fields(resp)
    if code == "unauthenticated" or resp.status == 401:
        return CLS_INVALID, code or "unauthenticated"
    if code == "limit_exceeded":
        return CLS_QUOTA, code
    return classify_generic(resp)


def classify_openai_l1(resp):
    """L1 single 1-token generation: error.code drives the verdict."""
    if resp.error is not None:
        return CLS_NETWORK, resp.error, "1tok"
    code, _, msg = _err_fields(resp)
    if resp.status == 200:
        return CLS_OK, "1-token generation ok", "1tok"
    if code == "insufficient_quota":
        return CLS_QUOTA, "insufficient_quota on generation", "1tok"
    if code in ("invalid_api_key", "incorrect_api_key"):
        return CLS_INVALID, code, "1tok"
    if code in ("model_not_found", "model_not_found_error") or \
            (resp.status == 404):
        return CLS_MODEL, code or "404", "1tok"
    cls, detail = classify_generic(resp)
    return cls, detail, "1tok"


# ------------------------------------------------------------- provider checks

def _row(provider, env_name, src, val):
    match, hashes = source_match(src)
    present = val is not None
    return {
        "provider": provider,
        "key_env": env_name,
        "key_present": present,
        "key_len": len(val) if present else 0,
        "key_sha8": sha8(val) if present else None,
        "key_sources": sorted(src.keys()),
        "source_match": match,
        "source_sha8": hashes,
        "l0_status": "none",
        "classification": CLS_MISSING if not present else None,
        "detail": "key absent (process/user/machine/secrets)" if not present else "",
        "fix": FIX.get(provider, ""),
        "latency_ms": None,
        "l1": None,
    }


def _finalize(row, results, paid_ctx):
    """results: list of (l0_status, cls, detail, latency_ms, extra)."""
    ok = all(r[0] == "ok" for r in results)
    worst = next((r for r in results if r[0] == "fail"), results[-1])
    row["l0_status"] = "ok" if ok else "fail"
    row["classification"] = CLS_OK if ok else worst[1]
    row["detail"] = "; ".join(d for _, _, d, _, _ in results if d)[:300]
    row["latency_ms"] = sum(r[3] for r in results)
    for _, _, _, _, extra in results:
        if extra:
            row.setdefault("extra", {}).update(extra)
    if row["source_match"] == "MISMATCH" and row["classification"] != CLS_MISSING:
        # 소스별 불일치는 그 자체로 결함 — resolved key가 OK여도 표기한다.
        row["classification"] = CLS_MISMATCH
        row["detail"] = (row["detail"] + "; env-vs-secrets value mismatch").strip("; ")
    if paid_ctx is not None and row["l0_status"] == "ok" and \
            row["classification"] in (CLS_OK, CLS_MISMATCH):
        row["l1"] = paid_ctx()
    return row


def check_jev(keyctx, http, paid):
    name, src, val = keyctx
    row = _row("jev", name, src, val)
    if val is None:
        return row
    # L0-a credits: official free balance endpoint (verified 200 on 2026-09-29).
    # https://vercel.com/docs/ai-gateway (credits API)
    r = http("GET", "https://ai-gateway.vercel.sh/v1/credits",
             {"Authorization": "Bearer " + val})
    cls, detail = classify_jev(r)
    results = [("ok" if cls == CLS_OK else "fail", cls, "credits: " + detail,
                r.latency_ms, {})]
    if cls in (CLS_INVALID, CLS_FORBIDDEN):
        return _finalize(row, results, None)
    if r.status == 200:
        j = r.json() or {}
        if isinstance(j, dict):
            results[0][4].update({"credits": {k: j.get(k) for k in
                                              ("balance", "total_used") if k in j}})
    # L0-b models list (no auth) — is the configured model still listed?
    r2 = http("GET", "https://ai-gateway.vercel.sh/v1/models")
    if r2.error is not None:
        results.append(("fail", CLS_NETWORK, "models: " + r2.error, r2.latency_ms, {}))
    elif r2.status == 200:
        j2 = r2.json()
        if not isinstance(j2, dict):
            results.append(("fail", CLS_UNKNOWN,
                            "models: unparseable body (truncated=%s)" % r2.truncated,
                            r2.latency_ms, {}))
            return _finalize(row, results, (lambda: l1_jev()) if paid else None)
        ids = [m.get("id") for m in j2.get("data", []) if isinstance(m, dict)]
        want = os.environ.get("AWX_JEV_MODEL", "typesafe-ai/jev")
        if want in ids:
            results.append(("ok", CLS_OK, "models: %s listed (%d total)" % (want, len(ids)),
                            r2.latency_ms, {}))
        else:
            results.append(("fail", CLS_MODEL, "models: %s absent (%d total)" % (want, len(ids)),
                            r2.latency_ms, {}))
    else:
        results.append(("fail", CLS_UNKNOWN, "models: http %s" % r2.status, r2.latency_ms, {}))
    return _finalize(row, results,
                     (lambda: l1_jev()) if paid else None)


def l1_jev():
    """L1: exactly one evaluate call via the existing delegate (node mjs wrapper)."""
    cmd = [sys.executable, "-B", str(ROOT / "scripts" / "jev_api_smoke.py"), "--live"]
    t0 = datetime.datetime.now(datetime.timezone.utc)
    try:
        p = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True, timeout=60)
        j = None
        for line in reversed((p.stdout or "").strip().splitlines()):
            try:
                cand = json.loads(line)
                if isinstance(cand, dict) and "jevResult" in cand:
                    j = cand
                    break
            except ValueError:
                continue
        res = (j or {}).get("jevResult") or ("exit%d" % p.returncode)
        return {"ran": True, "tool": "jev_api_smoke.py --live", "at": t0.isoformat(),
                "verdict": res, "httpStatus": (j or {}).get("httpStatus"),
                "estCost": "1 evaluate call"}
    except (OSError, subprocess.TimeoutExpired) as e:
        return {"ran": False, "tool": "jev_api_smoke.py --live", "error": str(e)[:160]}


def check_openai(keyctx, http, paid):
    name, src, val = keyctx
    row = _row("openai", name, src, val)
    if val is None:
        return row
    # L0: GET /v1/models — official free list; NOTE stays 200 even at zero balance,
    # so quota failure is only provable via L1 generation.
    # https://platform.openai.com/docs/api-reference/models/list
    r = http("GET", "https://api.openai.com/v1/models",
             {"Authorization": "Bearer " + val})
    cls, detail = classify_generic(r)
    ids = []
    if r.status == 200:
        jo = r.json()
        if not isinstance(jo, dict):
            cls, detail = CLS_UNKNOWN, "unparseable body (truncated=%s)" % r.truncated
        else:
            ids = [m.get("id") for m in jo.get("data", []) if isinstance(m, dict)]
            detail = "models=%d" % len(ids)
    results = [("ok" if cls == CLS_OK else "fail", cls, "models: " + detail, r.latency_ms, {})]
    paid_fn = None
    if paid:
        model = pick_cheapest_openai_model(ids)
        paid_fn = (lambda m=model: l1_openai(val, http, m)) if model else \
            (lambda: {"ran": False, "error": "no chat-capable model in /v1/models"})
    return _finalize(row, results, paid_fn)


def pick_cheapest_openai_model(ids):
    """Heuristic cheapest chat model — no hardcoded id. Cheapness: nano<mini<lite/flash<base."""
    cand = [i for i in ids if isinstance(i, str) and
            re.match(r"^(gpt-|o\d|chatgpt-)", i) and
            not re.search(r"(audio|realtime|transcribe|tts|image|instruct|moderation|embedding|search|preview-internal)", i)]
    if not cand:
        return None

    def score(i):
        s = 50.0
        if "nano" in i:
            s -= 40
        elif "mini" in i:
            s -= 30
        elif "lite" in i or "flash" in i:
            s -= 25
        if re.search(r"gpt-3\.5", i):
            s -= 10
        if "pro" in i or "codex" in i:
            s += 25
        s += len(i) / 100.0
        return s
    return min(cand, key=score)


def l1_openai(key, http, model):
    body = {"model": model,
            "messages": [{"role": "user", "content": "ping"}],
            "max_completion_tokens": 1}
    r = http("POST", "https://api.openai.com/v1/chat/completions",
             {"Authorization": "Bearer " + key}, body)
    cls, detail, cost = classify_openai_l1(r)
    return {"ran": True, "model": model, "classification": cls, "detail": detail,
            "httpStatus": r.status, "latency_ms": r.latency_ms, "estCost": cost}


def check_gemini(keyctx, http, paid):
    name, src, val = keyctx
    row = _row("gemini", name, src, val)
    if val is None:
        return row
    base = "https://generativelanguage.googleapis.com/v1beta"
    # L0-a model list — https://ai.google.dev/api/models (x-goog-api-key header)
    r = http("GET", base + "/models", {"x-goog-api-key": val})
    cls, detail = classify_gemini(r)
    listed = []
    if r.status == 200:
        jr = r.json()
        if not isinstance(jr, dict):
            cls, detail = CLS_UNKNOWN, "models: unparseable body (truncated=%s)" % r.truncated
        else:
            listed = [str(mm.get("name", "")).split("/")[-1]
                      for mm in jr.get("models", []) if isinstance(mm, dict)]
            detail = "models=%d" % len(listed)
    results = [("ok" if cls == CLS_OK else "fail", cls, "models: " + detail, r.latency_ms, {})]
    if cls in (CLS_INVALID, CLS_FORBIDDEN):
        return _finalize(row, results, None)
    # L0-b countTokens on the configured model — free, proves model access.
    # https://ai.google.dev/api/tokens
    model = os.environ.get("GEMINI_GATEWAY_DEFAULT_MODEL", "gemini-2.5-flash")
    r2 = http("POST", "%s/models/%s:countTokens" % (base, model),
              {"x-goog-api-key": val},
              {"contents": [{"parts": [{"text": "ping"}]}]})
    if r2.status == 404:
        in_list = model in listed
        results.append(("fail", CLS_MODEL if not in_list else CLS_UNKNOWN,
                        "countTokens: %s 404%s" % (
                            model,
                            " — model IS in /models list; countTokens rejected"
                            if in_list else " — absent from /models list"),
                        r2.latency_ms, {"model": model, "modelListed": in_list}))
    else:
        cls2, d2 = classify_gemini(r2)
        results.append(("ok" if cls2 == CLS_OK else "fail", cls2,
                        "countTokens(%s): %s" % (model, d2), r2.latency_ms, {}))
    paid_fn = (lambda: l1_gemini(val, http, base, model)) if paid else None
    return _finalize(row, results, paid_fn)


def l1_gemini(key, http, base, model):
    r = http("POST", "%s/models/%s:generateContent" % (base, model),
             {"x-goog-api-key": key},
             {"contents": [{"parts": [{"text": "ping"}]}],
              "generationConfig": {"maxOutputTokens": 1}})
    cls, detail = classify_gemini(r)
    return {"ran": True, "model": model, "classification": cls, "detail": detail,
            "httpStatus": r.status, "latency_ms": r.latency_ms, "estCost": "1tok"}


def _bearer_list_check(row, val, url, provider, http, extra_headers=None, want_status=200):
    if val is None:
        return row
    h = {"Authorization": "Bearer " + val}
    h.update(extra_headers or {})
    r = http("GET", url, h)
    cls, detail = classify_generic(r)
    if provider == "soniox":
        cls, detail = classify_soniox(r)
    n = None
    if r.status == want_status:
        j = r.json()
        if isinstance(j, dict):
            data = j.get("data") or j.get("models") or j.get("indexes") or j.get("projects")
            if isinstance(data, list):
                n = len(data)
    detail = detail if n is None else "%s (%d entries)" % (detail, n)
    return _finalize(row, [("ok" if cls == CLS_OK else "fail", cls, detail, r.latency_ms, {})],
                     None)


def check_ollama(_keyctx, http, _paid):
    row = {"provider": "ollama", "key_env": "OLLAMA_HOST/LLM_BASE_URL",
           "key_present": True, "key_len": 0, "key_sha8": None,
           "key_sources": ["local"], "source_match": "n/a", "source_sha8": {},
           "l0_status": "none", "classification": None, "detail": "",
           "fix": FIX["ollama"], "latency_ms": None, "l1": None}
    raw = (os.environ.get("LLM_BASE_URL") or os.environ.get("OLLAMA_HOST")
           or "http://127.0.0.1:11434")
    base = re.sub(r"/v1/?$", "", raw if raw.startswith("http") else "http://" + raw)
    r = http("GET", base + "/api/tags")
    if r.error is not None or r.status != 200:
        cls, d = (CLS_NETWORK, r.error) if r.error else (CLS_UNKNOWN, "http %s" % r.status)
        return _finalize(row, [("fail", cls, "tags: " + str(d), r.latency_ms, {"endpoint": base})], None)
    models = [m.get("name") for m in (r.json() or {}).get("models", []) if isinstance(m, dict)]
    expected = _expected_ollama_models()
    missing = sorted(set(expected) - set(models))
    if missing:
        return _finalize(row, [("fail", CLS_MODEL,
                                "up; missing %d expected model(s): %s" % (len(missing), ",".join(missing[:5])),
                                r.latency_ms, {"endpoint": base, "modelCount": len(models)})], None)
    return _finalize(row, [("ok", CLS_OK, "up; %d models, all expected present" % len(models),
                            r.latency_ms, {"endpoint": base, "modelCount": len(models)})], None)


def _expected_ollama_models():
    """installed_models.all entries from configs/api-routing.yaml (line scan, no yaml dep)."""
    try:
        lines = ROUTING.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    out, in_all = [], False
    for ln in lines:
        if re.match(r"^\s*all:\s*$", ln):
            in_all = True
            continue
        if in_all:
            m = re.match(r'^\s*-\s*"([^"]+)"\s*$', ln)
            if m:
                out.append(m.group(1))
            elif re.match(r"^\s*[a-z_]+:", ln):
                break
    return out


def check_serpapi(keyctx, http, _p):
    name, src, val = keyctx
    row = _row("serpapi", name, src, val)
    if val is None:
        return row
    # account.json is the official no-quota account introspection endpoint.
    # https://serpapi.com/account-api — key travels as api_key query param (never logged).
    r = http("GET", "https://serpapi.com/account.json?api_key=" + val)
    cls, detail = classify_generic(r)
    if r.status == 200:
        j = r.json() or {}
        detail = "plan=%s searches_left=%s" % (j.get("plan_name"),
                                             j.get("plan_searches_left"))
    return _finalize(row, [("ok" if cls == CLS_OK else "fail", cls, detail, r.latency_ms, {})],
                     None)


def check_deepgram(keyctx, http, _p):
    name, src, val = keyctx
    row = _row("deepgram", name, src, val)
    if val is None:
        return row
    # Management API list-projects — free auth check.
    # https://developers.deepgram.com/reference/get-projects
    r = http("GET", "https://api.deepgram.com/v1/projects",
             {"Authorization": "Token " + val})
    cls, detail = classify_generic(r)
    return _finalize(row, [("ok" if cls == CLS_OK else "fail", cls, detail, r.latency_ms, {})],
                     None)


def check_anthropic(keyctx, http, _p):
    name, src, val = keyctx
    row = _row("anthropic", name, src, val)
    if val is None:
        return row
    # https://docs.anthropic.com/en/api/models-list
    r = http("GET", "https://api.anthropic.com/v1/models",
             {"x-api-key": val, "anthropic-version": "2023-06-01"})
    cls, detail = classify_generic(r)
    return _finalize(row, [("ok" if cls == CLS_OK else "fail", cls, detail, r.latency_ms, {})],
                     None)


def check_pinecone(keyctx, http, _p):
    name, src, val = keyctx
    row = _row("pinecone", name, src, val)
    if val is None:
        return row
    # https://docs.pinecone.io/reference/api/2025-10/control-plane/list_indexes
    r = http("GET", "https://api.pinecone.io/indexes", {"Api-Key": val})
    cls, detail = classify_generic(r)
    return _finalize(row, [("ok" if cls == CLS_OK else "fail", cls, detail, r.latency_ms, {})],
                     None)


def check_openrouter(keyctx, http, _p):
    name, src, val = keyctx
    row = _row("openrouter", name, src, val)
    if val is None:
        return row
    # Key introspection incl. usage/limit — https://openrouter.ai/docs/api-reference/get-credits
    r = http("GET", "https://openrouter.ai/api/v1/auth/key",
             {"Authorization": "Bearer " + val})
    cls, detail = classify_generic(r)
    if r.status == 200:
        d = (r.json() or {}).get("data") or {}
        detail = "usage=%s limit=%s free=%s" % (d.get("usage"), d.get("limit"), d.get("is_free_tier"))
        if isinstance(d.get("limit"), (int, float)) and isinstance(d.get("usage"), (int, float)) \
                and d["limit"] > 0 and d["usage"] >= d["limit"]:
            cls, detail = CLS_QUOTA, detail + " (limit reached)"
    return _finalize(row, [("ok" if cls == CLS_OK else "fail", cls, detail, r.latency_ms, {})],
                     None)


def check_naver(keyctx, http, _p):
    cid_name, cid_src, cid = resolve_key(["NAVER_CLIENT_ID", "NAVER_KEYS"])
    sec_name, sec_src, sec = resolve_key(["NAVER_CLIENT_SECRET"])
    row = merged_key_row("naver", cid_name + "+" + sec_name,
                         [(cid_name, cid_src, cid), (sec_name, sec_src, sec)])
    if cid is None or sec is None:
        return row
    # free-tier-only API (no paid meter) — 1-result search is 0 KRW.
    # https://developers.naver.com/docs/serviceapi/search/blog/blog.md (webkr same shape)
    r = http("GET", "https://openapi.naver.com/v1/search/webkr.json?query=test&display=1",
             {"X-Naver-Client-Id": cid, "X-Naver-Client-Secret": sec})
    code, _, msg = _err_fields(r)
    cls, detail = classify_generic(r)
    if r.status == 200:
        detail = "items=%d" % len((r.json() or {}).get("items") or [])
    elif code:
        detail = "%s %s" % (code, msg[:80])
    return _finalize(row, [("ok" if cls == CLS_OK else "fail", cls, detail, r.latency_ms, {})],
                     None)


def check_kakao(keyctx, http, _p):
    name, src, val = keyctx
    row = _row("kakao", name, src, val)
    if val is None:
        return row
    # Kakao Local is free-tier-only — 1-result keyword search, KakaoAK auth.
    # https://developers.kakao.com/docs/latest/ko/local/dev-guide
    r = http("GET", "https://dapi.kakao.com/v2/local/search/keyword.json?query=test&size=1",
             {"Authorization": "KakaoAK " + val})
    cls, detail = classify_generic(r)
    return _finalize(row, [("ok" if cls == CLS_OK else "fail", cls, detail, r.latency_ms, {})],
                     None)


def check_upstash(keyctx, http, _p, provider, url_envs, tok_envs):
    uname, usrc, url = resolve_key(url_envs)
    tname, tsrc, tok = resolve_key(tok_envs)
    row = merged_key_row(provider, uname + "+" + tname,
                         [(uname, usrc, url), (tname, tsrc, tok)])
    if url is None or tok is None:
        return row
    # GET /info — official free index info (no quota metered read/write).
    # https://upstash.com/docs/vector/api/endpoints/info
    r = http("GET", url.rstrip("/") + "/info", {"Authorization": "Bearer " + tok})
    cls, detail = classify_generic(r)
    if r.status == 200:
        j = (r.json() or {}).get("result") or r.json() or {}
        if isinstance(j, dict):
            detail = "vectorCount=%s dim=%s" % (j.get("vectorCount"), j.get("dimension"))
    return _finalize(row, [("ok" if cls == CLS_OK else "fail", cls, detail, r.latency_ms, {})],
                     None)


def l1_brave(val):
    r = http_request("GET", "https://api.search.brave.com/res/v1/web/search?q=test&count=1",
                     {"X-Subscription-Token": val, "Accept": "application/json"})
    cls, detail = classify_generic(r)
    return {"ran": True, "classification": cls, "detail": detail,
            "httpStatus": r.status, "latency_ms": r.latency_ms, "estCost": "1 query"}


def l1_tavily(val):
    r = http_request("POST", "https://api.tavily.com/search",
                     {"Authorization": "Bearer " + val},
                     {"query": "test", "max_results": 1, "search_depth": "basic"})
    cls, detail = classify_generic(r)
    return {"ran": True, "classification": cls, "detail": detail,
            "httpStatus": r.status, "latency_ms": r.latency_ms, "estCost": "1 search"}


# provider id -> (env candidates, check fn). Order = output order.
PROVIDERS = [
    ("jev", ["AI_GATEWAY_API_KEY"], check_jev),
    ("openai", ["OPENAI_API_KEY", "OPENAI_EMBED_FALLBACK_KEY"], check_openai),
    ("gemini", ["GEMINI_API_KEY", "GOOGLE_API_KEY"], check_gemini),
    ("ollama", [], check_ollama),
    ("groq", ["GROQ_API_KEY"],
     lambda k, h, p: _bearer_list_check(_row("groq", *k), k[2],
                                        "https://api.groq.com/openai/v1/models", "groq", h)),
    ("anthropic", ["ANTHROPIC_API_KEY"], check_anthropic),
    ("mistral", ["MISTRAL_API_KEY"],
     lambda k, h, p: _bearer_list_check(_row("mistral", *k), k[2],
                                        "https://api.mistral.ai/v1/models", "mistral", h)),
    ("openrouter", ["OPENROUTER_API_KEY"], check_openrouter),
    ("zai", ["ZAI_API_KEY"],
     lambda k, h, p: _bearer_list_check(_row("zai", *k), k[2],
                                        "https://api.z.ai/api/paas/v4/models", "zai", h)),
    ("opencode", ["OPENCODE_API_KEY", "OPENCODE_GO_API_KEY"],
     lambda k, h, p: _bearer_list_check(_row("opencode", *k), k[2],
                                        "https://opencode.ai/zen/v1/models", "opencode", h)),
    ("soniox", ["SONIOX_API_KEY"],
     lambda k, h, p: _bearer_list_check(_row("soniox", *k), k[2],
                                        "https://api.soniox.com/v1/models", "soniox", h)),
    ("deepgram", ["DEEPGRAM_API_KEY", "DEEPGRAM_API_KEY_SECONDARY"], check_deepgram),
    ("pinecone", ["PINECONE_API_KEY"], check_pinecone),
    ("serpapi", ["SERPAPI_API_KEY"], check_serpapi),
    ("naver", [], check_naver),
    ("kakao", ["KAKAO_REST_API_KEY"], check_kakao),
    ("upstash_vector", [],
     lambda k, h, p: check_upstash(k, h, p, "upstash_vector",
                                   ["UPSTASH_VECTOR_URL", "UPSTASH_VECTOR_REST_URL"],
                                   ["UPSTASH_VECTOR_TOKEN", "UPSTASH_VECTOR_API_KEY",
                                    "UPSTASH_VECTOR_REST_TOKEN"])),
    ("upstash_redis", [],
     lambda k, h, p: check_upstash(k, h, p, "upstash_redis",
                                   ["UPSTASH_REDIS_REST_URL"],
                                   ["UPSTASH_REDIS_REST_TOKEN"])),
]


def probe_provider(pid, envs, fn, paid, http=None):
    http = http or http_request
    if pid in ("ollama", "naver", "upstash_vector", "upstash_redis"):
        return fn(None, http, paid)
    keyctx = resolve_key(envs)
    row = fn(keyctx, http, paid)
    # Bounded alternate-source probe: on env-vs-secrets mismatch where the
    # resolved key failed, one extra *free* L0 run with a differing source value
    # tells WHICH copy is stale instead of just flagging the mismatch.
    name, src, val = keyctx
    if (row.get("source_match") == "MISMATCH" and row.get("l0_status") == "fail"
            and val is not None):
        alt = next(((s, v) for s, v in sorted(src.items()) if sha8(v) != sha8(val)),
                   None)
        if alt is not None:
            alt_row = fn((name, {alt[0]: alt[1]}, alt[1]), http, False)
            row["alt_probe"] = {"source": alt[0],
                                "l0_status": alt_row.get("l0_status"),
                                "classification": alt_row.get("classification"),
                                "detail": alt_row.get("detail")}
            row["detail"] = (row["detail"] + "; alt[%s]: %s"
                             % (alt[0], str(alt_row.get("detail") or ""))[:120]
                             )[:300]
    return row


def quota_providers(paid):
    """Providers with no official free L0 — marked, only called under --paid."""
    rows = []
    for pid, envs, l1 in (("brave", ["BRAVE_API_KEY_FREE", "BRAVE_API_KEY"], l1_brave),
                          ("tavily", ["TAVILY_API_KEY"], l1_tavily),
                          ("devin", ["DEVIN_API_KEY"], None)):
        name, src, val = resolve_key(envs)
        row = _row(pid, name, src, val)
        if val is None:
            rows.append(row)
            continue
        row["classification"] = None
        row["detail"] = "no official free introspection endpoint" + \
                        (" — L1 available via --paid" if l1 else " (platform key; not probed)")
        if paid and l1 is not None:
            row["l1"] = l1(val)
            c = row["l1"].get("classification")
            row["classification"] = c or CLS_UNKNOWN
            row["l0_status"] = "none"
        rows.append(row)
    return rows


# --------------------------------------------------------------------- output

def print_table(rows):
    cols = ["provider", "key_env", "present", "len", "sha8", "sources", "match",
            "L0", "class", "detail", "fix", "ms", "L1"]
    widths = [14, 24, 7, 4, 8, 18, 8, 6, 20, 46, 40, 6, 16]
    line = " | ".join(c.ljust(w)[:w] for c, w in zip(cols, widths))
    print(line)
    print("-" * len(line))
    for r in rows:
        l1 = r.get("l1")
        l1s = ""
        if l1:
            l1s = (l1.get("classification") or l1.get("verdict") or
                   ("ran" if l1.get("ran") else "skipped"))
        vals = [r["provider"], r["key_env"], "yes" if r["key_present"] else "NO",
                str(r["key_len"]), r["key_sha8"] or "-",
                ",".join(r["key_sources"]) or "-", r["source_match"],
                r["l0_status"], r["classification"] or "-",
                (r["detail"] or "")[:46], r["fix"][:40],
                str(r["latency_ms"] or "-"), l1s[:16]]
        print(" | ".join(str(v).ljust(w)[:w] for v, w in zip(vals, widths)))


def main():
    ap = argparse.ArgumentParser(
        description="API key health probe — L0 free checks default, --paid for 1-token L1")
    ap.add_argument("--only", help="comma-separated provider ids")
    ap.add_argument("--paid", action="store_true",
                    help="enable L1: exactly one minimal paid call per provider")
    ap.add_argument("--json", action="store_true", help="print full JSON to stdout")
    ap.add_argument("--no-save", action="store_true", help="skip writing the JSON artifact")
    args = ap.parse_args()

    only = {s.strip().lower() for s in args.only.split(",")} if args.only else None
    started = datetime.datetime.now(datetime.timezone.utc)

    tasks = [(pid, envs, fn) for pid, envs, fn in PROVIDERS if only is None or pid in only]
    rows = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        futs = {ex.submit(probe_provider, pid, envs, fn, args.paid): pid
                for pid, envs, fn in tasks}
        for f in concurrent.futures.as_completed(futs):
            pid = futs[f]
            try:
                rows.append(f.result())
            except Exception as e:
                rows.append({"provider": pid, "key_env": "", "key_present": None,
                             "key_len": 0, "key_sha8": None, "key_sources": [],
                             "source_match": "?", "source_sha8": {},
                             "l0_status": "fail", "classification": CLS_UNKNOWN,
                             "detail": "probe crash: %s" % str(e)[:120],
                             "fix": FIX.get(pid, ""), "latency_ms": None, "l1": None})
    if only is None or any(p in only for p in ("brave", "tavily", "devin")):
        rows += [r for r in quota_providers(args.paid)
                 if only is None or r["provider"] in only]
    order = {pid: i for i, (pid, _, _) in enumerate(PROVIDERS)}
    order.update({"brave": 90, "tavily": 91, "devin": 92})
    rows.sort(key=lambda r: order.get(r["provider"], 99))

    for r in rows:
        if r["key_present"] is False and r["classification"] is None:
            r["classification"] = CLS_MISSING

    fails = [r for r in rows
             if r["classification"] not in (CLS_OK, CLS_MISSING, None)]
    if args.paid:
        for r in rows:
            l1 = r.get("l1") or {}
            if l1.get("ran") and l1.get("classification") not in (None, CLS_OK):
                fails.append(r)
        print("[AWX][api-spend] " + json.dumps({
            "session": "api-health-" + started.strftime("%Y%m%d-%H%M%S"),
            "purpose": "key-health-L1", "tier": "paid_quality",
            "calls": sum(1 for r in rows if (r.get("l1") or {}).get("ran")),
            "why": "operator --paid flag", "caller": "api_key_health_probe.py"},
            ensure_ascii=False))

    result = {
        "schemaVersion": "awx.api-key-health.v1",
        "contract": "DEMO1-DEVIN-API-KEY-HEALTH-PROBE-20260929",
        "at": started.isoformat(),
        "paid": bool(args.paid),
        "providers": [{k: v for k, v in r.items() if not k.startswith("_")} for r in rows],
        "summary": {"total": len(rows), "ok": sum(1 for r in rows if r["classification"] == CLS_OK),
                    "missing": sum(1 for r in rows if r["classification"] == CLS_MISSING),
                    "failed": len({r["provider"] for r in fails}),
                    "rawValuesPrinted": 0},
    }

    out_path = None
    if not args.no_save:
        OUTDIR.mkdir(parents=True, exist_ok=True)
        out_path = OUTDIR / ("api-health-%s.json" % started.strftime("%Y%m%d-%H%M%S"))
        out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print_table(rows)
        if out_path:
            print("result=%s" % out_path)
        bad = [r for r in rows if r["classification"] not in (CLS_OK, CLS_MISSING, None)]
        if bad:
            print("FAILURES:")
            for r in bad:
                print("  %s: %s — %s -> %s" % (r["provider"], r["classification"],
                                              r["detail"], r["fix"]))
    return 0 if not {r["provider"] for r in fails} else 3


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # script-level error -> exit 2 per contract
        print(json.dumps({"status": "error", "error": "%s: %s" % (type(e).__name__, e)},
                         ensure_ascii=False))
        sys.exit(2)
