#!/usr/bin/env python3
"""Codex-credit (ChatGPT OAuth) model inspector for demo-1.

Reads .secrets/chatgpt_oauth_credentials.json through the existing
chatgpt_oauth_flow harness (valid_token() auto-refreshes inside the buffer),
calls GET /v1/models for the full models[] metadata objects, and optionally
probes selected models with ONE minimal /v1/responses SSE call each.

Wire contract (data/agent-handoff/chatgpt-oauth/CONTRACT.md): store:false +
stream:true; temperature/max_output_tokens/top_p/background/
previous_response_id are never sent; completion = response.completed only.

Spend guard: --probe hits the DEFAULT_PROBE set only; --models narrows or
widens that selection; --probe-all covers the whole synced catalog.
No retries, sequential calls. Token values stay masked - get-token remains
the sole raw channel owned by chatgpt_oauth_flow.py.

Output: console Markdown table; --out <json> persists the full result;
--md <path> writes the same table to a file.
Exit codes follow chatgpt_oauth_flow.py: 0 ok, 2 no/expired credentials,
3 refresh failed, 5 transport/usage error. Per-model probe failures are
data rows (classification column), not run failures.
"""
import argparse
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent / "apikit"))
import chatgpt_oauth_flow as flow  # noqa: E402

try:
    import common as apikit_common  # noqa: E402
except Exception:  # apikit unavailable -> local fallback classifier
    apikit_common = None

# U-1(a): minimal probe coverage - the general chat slug plus the two
# code-mode models named by the directive. --models / --probe-all override.
DEFAULT_PROBE = ("gpt-5.5", "gpt-6-astra", "gpt-5.6-luna")
BANNED_BODY_FIELDS = ("temperature", "max_output_tokens", "top_p",
                      "background", "previous_response_id")
# Canonical classes (docs/API_ROUTING_SPEC.md external-failure table).
STATUS_CLASS = {400: "BAD_REQUEST_SHAPE", 401: "KEY_INVALID_OR_EXPIRED",
                402: "QUOTA_OR_BALANCE", 403: "FORBIDDEN_REGION_OR_IP",
                404: "MODEL_NOT_FOUND", 408: "NETWORK", 429: "RATE_LIMIT"}
PLAN_GATE_RE = re.compile(
    r"upgrade your plan|only available (?:for|on|to) (?:the )?"
    r"(?:pro|enterprise|paid|higher)|current plan|plan[\s_-]?gate", re.I)
CODE_MODE_RE = re.compile(r"code[\s_-]?mode|tool_mode|codex[ -]?only", re.I)
APIKIT_SPEC = {"code_paths": (("error", "code"), ("error", "type"),
                              ("code",), ("type",))}


def classify_http(status, body_text):
    """(cls, official_code, detail) - apikit canonical vocabulary."""
    if apikit_common is not None:
        result = {"status": status, "text": body_text or ""}
        if status == -1:
            result["error_kind"] = (
                "timeout" if re.search(r"timed?\s*out|timeout",
                                       body_text or "", re.I) else "transport")
        return apikit_common.classify(APIKIT_SPEC, result)
    # Fallback mirrors the same canonical strings.
    msg, code = "", None
    try:
        parsed = json.loads(body_text or "{}")
        err = parsed.get("error") if isinstance(parsed, dict) else None
        if isinstance(err, dict):
            msg, code = err.get("message") or "", err.get("code") or err.get("type")
    except ValueError:
        parsed = None
    if status == -1:
        return "NETWORK", None, "network:transport"
    cls = STATUS_CLASS.get(status, "SERVER_5XX" if status >= 500 else "UNKNOWN")
    if cls == "FORBIDDEN_REGION_OR_IP" and PLAN_GATE_RE.search(msg or body_text or ""):
        cls = "PLAN_GATE"
    return cls, code, ("code=%s" % code if code else "http=%d" % status) + (
        " msg=%s" % msg[:120] if msg else "")


def fetch_catalog(token, timeout):
    st, _, body = flow.http("GET", f"{flow.API_BASE}/models",
                            headers={"Authorization": f"Bearer {token}"},
                            timeout=timeout)
    if st != 200 or not isinstance(body, dict):
        text = body.get("_snippet", json.dumps(body)) if isinstance(body, dict) else str(body)
        cls, code, detail = classify_http(st, text)
        raise flow.FlowError(f"GET /v1/models HTTP {st}: {cls} {detail}", code=5)
    rows = body.get("models", body.get("data", []))
    return st, [m for m in rows if isinstance(m, dict)]


def probe_one(slug, token, prompt, timeout):
    """One bounded /v1/responses SSE ping; no retry."""
    t0 = time.monotonic()
    payload = {"model": slug,
               "input": [{"role": "user", "content": [
                   {"type": "input_text", "text": prompt}]}],
               "store": False, "stream": True}
    st, _, chunks = flow.post_stream(
        f"{flow.API_BASE}/responses",
        {"Authorization": f"Bearer {token}", "Accept": "text/event-stream"},
        payload, timeout=timeout)
    if st != 200:
        body = b"".join(chunks).decode("utf-8", "replace")
        ms = int((time.monotonic() - t0) * 1000)
        cls, code, detail = classify_http(st, body)
        restriction = "code_mode" if cls == "BAD_REQUEST_SHAPE" and CODE_MODE_RE.search(body) else None
        return {"ran": True, "httpStatus": st, "latencyMs": ms,
                "completed": False, "classification": cls,
                "restriction": restriction, "officialCode": code,
                "detail": (detail or "")[:200], "usage": {}, "outputChars": 0}
    evs = list(flow.sse_events(chunks))
    ms = int((time.monotonic() - t0) * 1000)
    types = [e for e, _ in evs]
    usage, terminal_error = {}, None
    text_chars = 0
    for e, d in evs:
        if e == "response.output_text.delta":
            try:
                text_chars += len(json.loads(d).get("delta", ""))
            except Exception:
                pass
        elif e in ("response.completed", "response.incomplete", "response.failed"):
            try:
                resp = json.loads(d).get("response") or {}
            except Exception:
                resp = {}
            usage = resp.get("usage") or usage
            if e != "response.completed":
                err = resp.get("error") or {}
                inc = resp.get("incomplete_details") or {}
                terminal_error = err.get("code") or inc.get("reason") or e
    completed = "response.completed" in types
    if completed:
        cls = "OK"
    elif terminal_error:
        cls = "TERMINAL_FAIL"
    else:
        cls = "NO_TERMINAL"
    return {"ran": True, "httpStatus": st, "latencyMs": ms,
            "completed": completed, "classification": cls,
            "terminalType": types[-1] if types else None,
            "eventCount": len(evs), "terminalError": terminal_error,
            "usage": usage, "outputChars": text_chars}


def probe_cell(p):
    if p is None:
        return "UNPROBED"
    if p.get("classification") == "OK":
        return "PASS (200 completed)"
    if p.get("restriction") == "code_mode":
        return "RESTRICTED (400 code-mode)"
    return p.get("classification") or "FAIL"


def usage_cell(p):
    if not p or not p.get("usage"):
        return "-"
    u = p["usage"]
    i, o = u.get("input_tokens"), u.get("output_tokens")
    if i is None and o is None:
        return "-"
    return f"{i}/{o} ({u.get('total_tokens', (i or 0) + (o or 0))}t)"


def meta_cell(m):
    skip = {"id", "slug", "name", "tool_mode", "description"}
    parts = []
    for k in sorted(m):
        v = m[k]
        if k in skip or v in (None, "", [], {}):
            continue
        if isinstance(v, (list, tuple)):
            v = ",".join(str(x) for x in v[:6])
        elif isinstance(v, dict):
            v = json.dumps(v, ensure_ascii=False)[:80]
        parts.append(f"{k}={v}")
    desc = str(m.get("description") or "")[:60]
    return ("; ".join(parts)[:160] or "-") + (f" | desc: {desc}" if desc else "")


def markdown(models, probes, ran_at):
    lines = ["| model slug | tool_mode | live metadata | probe | latency_ms | tokens in/out (total) |",
             "|---|---|---|---|---|---|"]
    for m in models:
        slug = m.get("slug") or m.get("id") or "?"
        p = probes.get(slug)
        lat = f"{p['latencyMs']}" if p and p.get("latencyMs") is not None else "-"
        lines.append("| %s | %s | %s | %s | %s | %s |" % (
            slug, m.get("tool_mode") or "general", meta_cell(m),
            probe_cell(p), lat, usage_cell(p)))
    lines.append("")
    lines.append(f"_catalog+probe ran at {ran_at}; store:false+stream:true; "
                 f"banned fields never sent: {', '.join(BANNED_BODY_FIELDS)}_")
    return "\n".join(lines)


def main(argv=None):
    p = argparse.ArgumentParser(prog="check_codex_credit_models.py",
                                description=__doc__)
    p.add_argument("--root", default=str(flow.default_root()))
    p.add_argument("--buffer", type=int, default=flow.REFRESH_BUFFER_SECONDS,
                   help="refresh when token expires within N seconds")
    p.add_argument("--models", default=None,
                   help="comma-separated slug filter for table/probe selection")
    p.add_argument("--probe", action="store_true",
                   help="one minimal /v1/responses ping per selected model "
                        "(default selection: " + ",".join(DEFAULT_PROBE) + ")")
    p.add_argument("--probe-all", action="store_true",
                   help="probe every catalog slug (explicit opt-in)")
    p.add_argument("--prompt", default="Reply with the word OK only.")
    p.add_argument("--timeout", type=int, default=120, help="per-probe seconds")
    p.add_argument("--catalog-timeout", type=int, default=flow.TIMEOUT)
    p.add_argument("--out", default=None, help="write full JSON result to path")
    p.add_argument("--md", default=None, help="write markdown table to path")
    args = p.parse_args(argv)

    root = Path(args.root)
    creds = flow.valid_token(root, flow.http, time.time(), args.buffer)
    st, rows = fetch_catalog(creds["access_token"], args.catalog_timeout)
    if args.models:
        wanted = {s.strip() for s in args.models.split(",") if s.strip()}
        rows = [m for m in rows if (m.get("slug") or m.get("id")) in wanted]

    probes = {}
    if args.probe or args.probe_all:
        if args.probe_all:
            selected = [m.get("slug") or m.get("id") for m in rows]
        elif args.models:
            selected = [m.get("slug") or m.get("id") for m in rows]
        else:
            have = {m.get("slug") or m.get("id") for m in rows}
            selected = [s for s in DEFAULT_PROBE if s in have]
        for slug in selected:
            if not slug:
                continue
            try:
                probes[slug] = probe_one(slug, creds["access_token"],
                                         args.prompt, args.timeout)
            except Exception as e:
                probes[slug] = {"ran": True, "httpStatus": -1, "completed": False,
                                "classification": "NETWORK",
                                "detail": f"{type(e).__name__}: {e}"[:200],
                                "usage": {}}
            print(f"probe {slug}: {probe_cell(probes[slug])} "
                  f"{usage_cell(probes[slug])}", file=sys.stderr)

    ran_at = datetime.now(timezone.utc).isoformat()
    doc = {"schemaVersion": "awx.codex-credit-model-inspect.v1",
           "ranAtUtc": ran_at,
           "account": {"client_id": creds.get("client_id"),
                       "access_token": flow.mask(creds["access_token"]),
                       "expires_at_kst": flow.kst(creds.get("expires_at", 0))},
           "catalog": {"httpStatus": st, "count": len(rows),
                       "rawKeyUnion": sorted({k for m in rows for k in m})},
           "probePolicy": {"promptChars": len(args.prompt),
                           "store": False, "stream": True,
                           "bannedFields": list(BANNED_BODY_FIELDS),
                           "selection": sorted(probes)},
           "models": [{"slug": m.get("slug") or m.get("id"), "meta": m,
                       "probe": probes.get(m.get("slug") or m.get("id"))}
                      for m in rows]}

    table = markdown(rows, probes, ran_at)
    print(table)
    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(doc, ensure_ascii=False, indent=1),
                            encoding="utf-8")
        print(f"json -> {out_path}", file=sys.stderr)
    if args.md:
        md_path = Path(args.md)
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text(table + "\n", encoding="utf-8")
        print(f"md -> {md_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except flow.FlowError as e:
        print(f"ERROR exit={e.code}: {e}", file=sys.stderr)
        sys.exit(e.code)
