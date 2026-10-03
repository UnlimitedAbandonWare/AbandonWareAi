#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""p6r2dbg_owner_route_matrix.py — owner-scoped route matrix for Codex P6-R2 WP1.

Extends scripts/p6dbg_demo_route_matrix.py (imports its controller extractor and
the InterviewDemoFilter simulator). Adds what R2 needs and P6 did not have:

  * filter/chain ORDER table — FilterRegistrationBean @Order/setOrder and every
    SecurityFilterChain bean's @Order + securityMatcher + authorizeHttpRequests
    rules, in declared order (Spring: first matching rule wins).
  * owner-check candidates — existing run/session ownership checks
    (file:method) that WP1 should reuse instead of new auth.
  * expected x observed matrix — anonymous A / anonymous B / admin x route.
    Own run or own session => expected ALLOW; other's => expected DENY.
    DELETE sessions / attachments / transcribe / models install+recheck /
    rag query+probe / cluster/* / traces html => expected 404 (unchanged).
    /api/chat/sync and demo=off rows => existing behaviour (expected ALLOW).
    Rows where expected != observed are flagged MISMATCH — this is the R1
    (T01_TRACE) input for Codex.

Modes:
  static (default) — pure source scan, no server needed.
  live   --base http://127.0.0.1:<port> — probes an ALREADY-RUNNING server with
           synthetic clients A/B (distinct ownerKey cookies held in memory only;
           never written to output). Records HTTP status + reasonCode/error +
           request-id header only. No cookie/token/body persisted, no answer
           generation is judged (HTTP 200 / SSE start is never a pass).
           Admin row is SKIP — no synthetic credentials are fabricated.
           No listener -> NOT_RUN.

Output: data/agent-handoff/devin-p6-r2/t01-owner-matrix.{md,json}
Exit 0 wrote matrix · 2 input missing (no main/java / import failure) ·
3 live base unreachable (matrix still written with live=NOT_RUN).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    import p6dbg_demo_route_matrix as drm
except ImportError as exc:  # Codex may be editing the P6 tool; fail loud.
    print(json.dumps({"status": "FAIL", "reason": f"import p6dbg_demo_route_matrix: {exc}"}))
    sys.exit(2)

SCHEMA = "devin-p6-r2.owner-route-matrix.v1"
OUT_MD = "data/agent-handoff/devin-p6-r2/t01-owner-matrix.md"
OUT_JSON = "data/agent-handoff/devin-p6-r2/t01-owner-matrix.json"

CLIENTS = ("anonA", "anonB", "admin")

# ----------------------------------------------------------------- routes --
# (key, method, path-template, expected-own, expected-other, expected-admin,
#  kind)   kind: run|session|kept404|unchanged
ROUTE_SPEC = [
    ("stream-own",       "POST", "/api/chat/stream",            "ALLOW", "DENY",  "ALLOW", "run"),
    ("stream-other",     "POST", "/api/chat/stream",            "DENY",  "DENY",  "ALLOW*", "run"),
    ("cancel-own",       "POST", "/api/chat/cancel",            "ALLOW", "DENY",  "ALLOW", "run"),
    ("cancel-other",     "POST", "/api/chat/cancel",            "DENY",  "DENY",  "ALLOW*", "run"),
    ("state-own",        "GET",  "/api/chat/state",             "ALLOW", "DENY",  "ALLOW", "run"),
    ("state-other",      "GET",  "/api/chat/state",             "DENY",  "DENY",  "ALLOW*", "run"),
    ("sessions-own",     "GET",  "/api/chat/sessions",          "ALLOW(own-list)", "ALLOW(own-list)", "ALLOW(all)", "session"),
    ("session-own",      "GET",  "/api/chat/sessions/{id}",     "ALLOW", "DENY",  "ALLOW", "session"),
    ("session-other",    "GET",  "/api/chat/sessions/{id}",     "DENY",  "DENY",  "ALLOW*", "session"),
    ("ack-own",          "POST", "/api/chat/ack",               "ALLOW-if-needed", "DENY", "ALLOW", "run"),
    ("ui-heartbeat-own", "POST", "/api/chat/ui-heartbeat",      "ALLOW-if-needed", "DENY", "ALLOW", "run"),
    ("sync",             "POST", "/api/chat/sync",              "ALLOW", "ALLOW", "ALLOW", "unchanged"),
    ("sessions-delete",  "DELETE", "/api/chat/sessions/{id}",   "404", "404", "404", "kept404"),
    ("attachments",      "POST", "/api/chat/attachments",       "404", "404", "404", "kept404"),
    ("transcribe",       "POST", "/api/chat/transcribe",        "404", "404", "404", "kept404"),
    ("models-install",   "POST", "/api/chat/models/install",    "404", "404", "404", "kept404"),
    ("models-recheck",   "POST", "/api/chat/models/recheck",    "404", "404", "404", "kept404"),
    ("rag-query",        "POST", "/api/rag/query",              "404", "404", "404", "kept404"),
    ("rag-probe",        "POST", "/api/rag/probe",              "404", "404", "404", "kept404"),
    ("cluster",          "GET",  "/api/cluster/status",         "404", "404", "404", "kept404"),
    ("traces-html",      "GET",  "/api/chat/sessions/{id}/traces/{sid}/html", "404", "404", "404", "kept404"),
]

# Owner-check candidates WP1 should reuse (name -> file-affinity regex).
OWNER_CANDIDATES = {
    "canAccessSession": r"ChatApiController\.java$",
    "authorizeCancellation": r"ChatApiController\.java$",
    "getSessionResponse": r"ChatApiController\.java$",
    "deleteSession": r"ChatApiController\.java$",
    "sessions": r"ChatApiController\.java$",
    "canAccess": r"ChatSessionAccessGuard\.java$",
    "authorize": r"ChatSessionAccessGuard\.java$",
    "ownerKey": r"ClientOwnerKeyResolver\.java$",
    "resolveOwnerKey": r"OwnerKeyResolver\.java$",
    "doFilter": r"OwnerKeyBootstrapFilter\.java$",
    "acknowledgeExact": r"ChatRunRegistry\.java$",
    "acknowledgeFinalDeliveryExact": r"ChatRunRegistry\.java$",
    "cancelSessionForDeletion": r"ChatRunRegistry\.java$",
    "getSessionsForUser": r"ChatHistoryService\w*\.java$",
    "getAllSessionsForAdmin": r"ChatHistoryService\w*\.java$",
    "getSessionWithMessages": r"ChatHistoryService\w*\.java$",
    "forAnonymous": r"AttachmentOwnerIdentity\.java$",
    "forActor": r"AttachmentOwnerIdentity\.java$",
    "doFilterInternal": r"ChatOpenSecurityConfig\.java$",
    "chatOpenChain": r"ChatOpenSecurityConfig\.java$",
    "doFilter": r"ChatGenerationAdmissionFilter\.java$",
    "tryAcquire": r"PublicChatAdmissionGuard\.java$",
    "requireChatAdmission": r"ChatApiController\.java$",
    "validateChat": r"PublicRequestBudgetGuard\.java$",
    "displayRequest": r"ChatOpenSecurityConfig\.java$",
}

METHOD_SIG_RE = re.compile(
    r"(?:public|private|protected|static|final|synchronized|\s)+[\w.<>\[\],?]*\s*"
    r"(?P<name>[A-Za-z_]\w*)\s*\([^;{]*\)\s*(?:throws\s+[^{]+)?\{")
ORDER_RE = re.compile(r"@Order\s*\(\s*([^)]+)\)")
COND_PROP_RE = re.compile(
    r"(?:[\w.]*)ConditionalOnProperty\s*\(\s*name\s*=\s*\"([^\"]+)\""
    r"(?:\s*,\s*havingValue\s*=\s*\"([^\"]*)\")?")
SET_ORDER_RE = re.compile(r"setOrder\s*\(\s*([^)]+?)\s*\)")
FRB_RE = re.compile(r"FilterRegistrationBean<\s*([\w.$]+)\s*>")
NEW_FILTER_RE = re.compile(r"new\s+([A-Z]\w*(?:Filter|Filter\w*))\s*\(")
SFC_RE = re.compile(r"SecurityFilterChain\s+(\w+)\s*\(")
MATCHER_RE = re.compile(r"antMatcher\s*\(\s*\"([^\"]+)\"\s*\)|\"(/[^\"]*)\"")
AUTHZ_RULE_RE = re.compile(
    r"(requestMatchers\s*\(.*?\)|anyRequest\s*\(\s*\))\s*"
    r"\.\s*(permitAll|authenticated|denyAll|hasRole|hasAnyRole)\s*\(",
    re.S)


def _brace_end(lines: list[str], start: int) -> int:
    depth, opened = 0, False
    for i in range(start, len(lines)):
        for ch in lines[i]:
            if ch == "{":
                depth += 1
                opened = True
            elif ch == "}" and opened:
                depth -= 1
                if depth <= 0:
                    return i
    return len(lines) - 1


def _preceding_annotations(lines: list[str], idx: int, span: int = 8) -> str:
    return "\n".join(lines[max(0, idx - span):idx])


def extract_filters_and_chains(root: Path) -> dict:
    """Ordered table: servlet filter registrations + SecurityFilterChain beans."""
    filters, chains = [], []
    for f in sorted(root.glob("main/java/**/*.java")):
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if "FilterRegistrationBean" not in text and "SecurityFilterChain" not in text \
                and "@Order" not in text:
            continue
        lines = text.splitlines()
        rel = str(f.relative_to(root)).replace("\\", "/")

        for i, ln in enumerate(lines):
            m = FRB_RE.search(ln)
            # a FilterRegistrationBean *method declaration* (has '(' after the
            # generic and a preceding @Bean) — not a plain variable/return type
            if m and "(" in ln[m.end():] and "@Bean" in _preceding_annotations(lines, i, 12):
                end = _brace_end(lines, i)
                body = "\n".join(lines[i:end + 1])
                order = SET_ORDER_RE.search(body)
                cond = COND_PROP_RE.search(_preceding_annotations(lines, i, 12) + ln)
                nf = NEW_FILTER_RE.search(body)
                entry = {
                    "file": rel, "line": i + 1, "filterClass": nf.group(1) if nf else m.group(1),
                    "order": (order.group(1) if order else "default"),
                    "conditionalOnProperty": (
                        f"{cond.group(1)}={cond.group(2) or '?'}" if cond else None)}
                if entry not in filters:
                    filters.append(entry)

            sm = SFC_RE.search(ln)
            if sm and "SecurityFilterChain" in ln:
                ann = _preceding_annotations(lines, i, 10)
                order = ORDER_RE.search(ann)
                end = _brace_end(lines, i)
                body = "\n".join(lines[i:end + 1])
                sec_m = re.search(r"securityMatchers?\s*\((.*?)\)\s*\n?\s*\.(?:csrf|cors|authorizeHttpRequests|securityContext)",
                                  body, re.S)
                matchers = MATCHER_RE.findall(sec_m.group(1)) if sec_m else []
                matchers = [a or b for a, b in matchers]
                rules = [{"matcher": rm.group(1)[:120], "verdict": rm.group(2)}
                         for rm in AUTHZ_RULE_RE.finditer(body)]
                chains.append({
                    "file": rel, "line": i + 1, "bean": sm.group(1),
                    "order": (order.group(1).strip() if order else "none"),
                    "securityMatchers": matchers, "authorizeRules": rules})

        # class-level @Order on Filter components (OwnerKeyBootstrapFilter etc.)
        if " implements Filter" in text or "OncePerRequestFilter" in text:
            om = ORDER_RE.search(text[: text.find("class ") if "class " in text else 4000])
            if om:
                filters.append({"file": rel, "line": 1,
                                "filterClass": f.name[:-5],
                                "order": om.group(1).strip(),
                                "conditionalOnProperty": None})
    def rank(o: str):
        o = re.sub(r"^(?:[\w.]*\.)?Ordered\.", "", o.strip())
        o = re.sub(r"^(?:[\w.]+\.)*HIGHEST_PRECEDENCE", "HIGHEST_PRECEDENCE", o)
        o = re.sub(r"^(?:[\w.]+\.)*LOWEST_PRECEDENCE", "LOWEST_PRECEDENCE", o)
        m = re.match(r"HIGHEST_PRECEDENCE\s*\+\s*(\d+)", o)
        if m:
            return -2**31 + int(m.group(1))
        if re.match(r"HIGHEST_PRECEDENCE\s*$", o):
            return -2**31
        m = re.match(r"LOWEST_PRECEDENCE\s*-\s*(\d+)", o)
        if m:
            return 2**31 - 1 - int(m.group(1))
        if re.match(r"LOWEST_PRECEDENCE\s*$", o):
            return 2**31 - 1
        try:
            return int(o)
        except ValueError:
            return 0
    filters.sort(key=lambda x: rank(x["order"]))
    chains.sort(key=lambda x: rank(x["order"]))
    return {"servletFilters": filters, "securityFilterChains": chains}


def ant_match(pattern: str, path: str) -> bool:
    """Minimal ant-style matcher: exact, /** suffix, /**, {var} segments."""
    if pattern == "/**":
        return True
    parts = []
    for seg in pattern.split("**"):
        esc = re.escape(seg)
        esc = re.sub(r"\\\{[^}]*\\\}", "[^/]+", esc)  # {var} -> one segment
        parts.append(esc)
    rx = ".*".join(parts)
    return bool(re.fullmatch(
        rx + ("/?" if not pattern.endswith("/**") else ""), path))


def first_matching_chain(chains: list[dict], path: str) -> dict:
    for ch in chains:
        for pat in ch.get("securityMatchers") or []:
            if ant_match(pat, path):
                return {"chain": ch["bean"], "file": ch["file"], "order": ch["order"],
                        "matchedPattern": pat}
    return {"chain": "(no chain matches → servlet filters only)", "matchedPattern": None}


def owner_check_candidates(root: Path) -> list[dict]:
    """file:method(:line) of existing ownership checks WP1 may reuse."""
    out = []
    files = list(root.glob("main/java/**/*.java"))
    for name, file_re in OWNER_CANDIDATES.items():
        hit = None
        for f in files:
            if not re.search(file_re, f.name):
                continue
            try:
                lines = f.read_text(encoding="utf-8", errors="replace").splitlines()
            except OSError:
                continue
            sig = re.compile(rf"\b{re.escape(name)}\s*\(")
            for i, ln in enumerate(lines):
                if sig.search(ln) and ("(" in ln):
                    decl = METHOD_SIG_RE.match(ln.strip()) or re.match(
                        r"\s*(?:static|final|public|private|protected).*" + re.escape(name), ln)
                    hit = {"method": name,
                           "file": str(f.relative_to(root)).replace("\\", "/"),
                           "line": i + 1, "decl": ln.strip()[:140]}
                    if METHOD_SIG_RE.match(ln.strip()):
                        break
            if hit:
                break
        out.append(hit or {"method": name, "file": None,
                           "line": None, "decl": "not-found"})
    return out


def observed_static(method: str, path: str) -> str:
    allowed, _why = drm.demo_filter_allows(method, path)
    return "ALLOW" if allowed else "404"


def expected_verdict(spec, client: str, demo_on: bool) -> str:
    key, method, path, own, other, admin, kind = spec
    if not demo_on:
        return "ALLOW(existing)"
    if kind == "unchanged":
        return "ALLOW(existing)"
    if kind == "kept404":
        return "404"
    if client == "admin":
        return admin
    if client == "anonA":
        return own
    return other


def build_matrix(observed_fn, demo_on: bool) -> list[dict]:
    rows = []
    for spec in ROUTE_SPEC:
        key, method, path, own, other, admin, kind = spec
        for client in CLIENTS:
            exp = expected_verdict(spec, client, demo_on)
            obs = observed_fn(method, path, client)
            rows.append({"route": key, "method": method, "path": path,
                         "client": client, "demo": "on" if demo_on else "off",
                         "kind": kind, "expected": exp, "observed": obs,
                         "verdict": verdict_for(exp, obs)})
    return rows


def verdict_for(expected: str, observed: str) -> str:
    """expected vs observed cell verdict.

    - expected 404 is satisfied by observed 404 (and by a DENY — both keep the
      route unreachable).
    - expected DENY is satisfied by any denial (403/404/DENY).
    - expected ALLOW-if-needed is not provable statically: NEEDS-TRACE unless
      the route is already reachable.
    """
    e, o = expected, str(observed)
    o_deny = o in ("404", "DENY") or o.startswith(("403", "401"))
    if e == "404":
        return "MATCH" if o_deny else "MISMATCH"
    if e.startswith("DENY"):
        return "MATCH" if o_deny else "MISMATCH"
    if e.startswith("ALLOW-if-needed"):
        return "NEEDS-TRACE" if o_deny else ("MATCH" if o.startswith("ALLOW") else "MISMATCH")
    if e.startswith("ALLOW"):
        return "MATCH" if o.startswith("ALLOW") else "MISMATCH"
    return "MISMATCH"


def normalize_observed(raw: str) -> str:
    if raw in ("404", "BLOCK(404)"):
        return "404"
    if raw.startswith("4") or raw == "DENY":
        return "DENY"
    return raw


# ------------------------------------------------------------------- live --
def _probe(base: str, method: str, path: str, owner: str | None,
           body: bytes | None, timeout: float = 8.0) -> dict:
    url = base.rstrip("/") + path
    req = urllib.request.Request(url, method=method, data=body)
    req.add_header("Accept", "application/json, text/event-stream")
    if owner:
        # synthetic cookie, kept in memory only — never written to output
        req.add_header("Cookie", f"ownerKey={owner}")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read(4096)
            status = resp.status
            headers = resp.headers
    except urllib.error.HTTPError as exc:
        status = exc.code
        headers = exc.headers
        try:
            raw = exc.read(2048)
        except OSError:
            raw = b""
    except (urllib.error.URLError, OSError) as exc:
        return {"status": "UNREACHABLE", "reason": str(exc)[:120]}
    reason = None
    try:
        j = json.loads(raw.decode("utf-8", errors="replace"))
        for k in ("reasonCode", "reason", "error", "code"):
            if k in j:
                reason = str(j[k])[:80]
                break
    except (json.JSONDecodeError, UnicodeDecodeError, AttributeError):
        reason = None
    req_id = None
    for h in ("X-Request-Id", "X-Trace-Id", "Request-Id"):
        if headers and headers.get(h):
            req_id = headers.get(h)
            break
    return {"status": status, "reasonCode": reason, "requestId": req_id}


LIVE_PROBES = [
    ("GET",  "/api/chat/state?sessionId=0",        True),
    ("GET",  "/api/chat/sessions?limit=1",         True),
    ("GET",  "/api/chat/sessions/0",               True),
    ("POST", "/api/chat/cancel",                   True),
    ("POST", "/api/chat/ack",                      True),
    ("POST", "/api/chat/sync",                     True),
    ("POST", "/api/chat/stream",                   True),
    ("DELETE", "/api/chat/sessions/0",             False),
    ("GET",  "/api/chat/sessions/0/traces/x/html", False),
    ("POST", "/api/chat/transcribe",               False),
    ("POST", "/api/rag/query",                     False),
    ("GET",  "/api/cluster/status",                False),
]


def live_probe(base: str) -> dict:
    keys = {"anonA": str(uuid.uuid4()), "anonB": str(uuid.uuid4())}  # memory only
    rows = []
    for method, path, both in LIVE_PROBES:
        for client in ("anonA", "anonB") if both else ("anonA",):
            r = _probe(base, method, path, keys[client],
                       b"{}" if method in ("POST",) else None)
            if r.get("status") == "UNREACHABLE":
                return {"status": "NOT_RUN", "reason": r["reason"], "rows": rows}
            rows.append({"client": client, "method": method, "path": path,
                         "status": r["status"], "reasonCode": r.get("reasonCode"),
                         "requestId": r.get("requestId")})
        rows.append({"client": "admin", "method": method, "path": path,
                     "status": "SKIP", "reasonCode": "no synthetic credential"})
    note = ("status+reasonCode+requestId only; no cookie/body/token stored; "
            "HTTP 200 or SSE start is not a success verdict")
    # inference (status-only, honest): a controller-level JSON reason on a
    # would-be-404 route proves the InterviewDemoFilter did NOT block it
    ctrl = [r for r in rows if r["path"].startswith("/api/chat/sessions/0")
            or r["path"] in ("/api/rag/query", "/api/chat/transcribe")]
    reached = [r for r in ctrl
               if r["status"] != "SKIP" and r.get("reasonCode")
               and r["reasonCode"] not in ("Not Found", "no synthetic credential")]
    if reached:
        seen = "; ".join(
            f"{r['method']} {r['path']}={r['reasonCode']}" for r in reached[:3])
        note += (" | demo-filter appears INACTIVE on this instance: "
                 f"{len(reached)} kept-404 route(s) reached the controller "
                 f"({seen})")
    return {"status": "DONE", "rows": rows, "note": note}


def render_md(payload: dict) -> str:
    L = ["# T01 owner route matrix (R2 WP1 input)", "",
         f"generated: {payload['generatedAtUtc']}  |  mode: {payload['mode']}", "",
         "## Filter / chain order (first matching rule wins)", "",
         "| order | kind | name | file:line | condition/matcher |", "|---|---|---|---|---|"]
    for flt in payload["static"]["servletFilters"]:
        L.append(f"| {flt['order']} | servlet-filter | `{flt['filterClass']}` | "
                 f"{flt['file']}:{flt['line']} | {flt.get('conditionalOnProperty') or '—'} |")
    for ch in payload["static"]["securityFilterChains"]:
        L.append(f"| {ch['order']} | SecurityFilterChain | `{ch['bean']}` | "
                 f"{ch['file']}:{ch['line']} | `{'; '.join(ch['securityMatchers'][:4])}` |")
    L += ["", "### authorizeHttpRequests rules (declared order)", ""]
    for ch in payload["static"]["securityFilterChains"]:
        L.append(f"- `{ch['bean']}` ({ch['file']}:{ch['line']}):")
        for r in ch["authorizeRules"]:
            L.append(f"  - `{r['matcher']}` → **{r['verdict']}**")
    L += ["", "## Owner-check candidates (reuse — file:method)", "",
          "| method | file | line | signature |", "|---|---|---|---|"]
    for c in payload["static"]["ownerCheckCandidates"]:
        L.append(f"| `{c['method']}` | {c['file'] or 'not-found'} | "
                 f"{c['line'] or '—'} | `{c['decl'][:100]}` |")
    L += ["", "## First-match resolution for watched routes", "",
          "| path | chain | matched pattern |", "|---|---|---|"]
    for fm in payload["static"]["firstMatch"]:
        L.append(f"| `{fm['path']}` | {fm['chain']} | `{fm.get('matchedPattern')}` |")
    L += ["", "## Expected × observed matrix (demo=on)", "",
          "| route | client | kind | expected | observed | verdict |",
          "|---|---|---|---|---|---|"]
    for r in payload["matrix_on"]:
        flag = " **MISMATCH**" if r["verdict"] == "MISMATCH" else ""
        L.append(f"| `{r['method']} {r['path']}` ({r['route']}) | {r['client']} | "
                 f"{r['kind']} | {r['expected']} | {r['observed']} | {r['verdict']}{flag} |")
    L += ["", "demo=off rows: expected ALLOW(existing) for every watched route — "
          "see JSON matrix_off.", ""]
    live = payload.get("live") or {}
    L += ["## Live probe", ""]
    if live.get("status") == "DONE":
        L += ["| client | method | path | status | reasonCode | requestId |",
              "|---|---|---|---|---|---|"]
        for r in live["rows"]:
            L.append(f"| {r['client']} | {r['method']} | `{r['path']}` | "
                     f"{r['status']} | {r.get('reasonCode') or '—'} | "
                     f"{r.get('requestId') or '—'} |")
        L += ["", "_status only — no cookie/body/token persisted; "
              "HTTP 200 / SSE start is not a pass._"]
    else:
        L.append(f"- {live.get('status', 'NOT_RUN')}: {live.get('reason', '')}")
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--base", default=None,
                    help="live mode: http://127.0.0.1:<port> of a running server")
    ap.add_argument("--out-md", default=OUT_MD)
    ap.add_argument("--out-json", default=OUT_JSON)
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    if not (root / "main/java").is_dir():
        print(json.dumps({"status": "FAIL", "reason": "no main/java"}))
        return 2

    static = extract_filters_and_chains(root)
    static["ownerCheckCandidates"] = owner_check_candidates(root)
    static["firstMatch"] = [
        {"path": p, **first_matching_chain(static["securityFilterChains"],
                                           p.replace("{id}", "1").replace("{sid}", "x"))}
        for _k, _m, p, *_rest in ROUTE_SPEC]

    matrix_on = build_matrix(
        lambda m, p, c: observed_static(m, p), demo_on=True)
    matrix_off = build_matrix(
        lambda m, p, c: "ALLOW(existing)", demo_on=False)

    payload = {"schemaVersion": SCHEMA,
               "generatedAtUtc": __import__("datetime").datetime.now(
                   __import__("datetime").timezone.utc).isoformat(),
               "mode": "live" if args.base else "static",
               "static": static, "matrix_on": matrix_on, "matrix_off": matrix_off,
               "live": {"status": "NOT_RUN", "reason": "no --base"}}
    rc = 0
    if args.base:
        if not args.base.startswith("http://127.0.0.1"):
            print(json.dumps({"status": "FAIL",
                              "reason": "--base must be http://127.0.0.1:<port>"}))
            return 3
        live = live_probe(args.base)
        payload["live"] = live
        if live["status"] != "DONE":
            rc = 3

    out_md = root / args.out_md
    out_js = root / args.out_json
    out_md.parent.mkdir(parents=True, exist_ok=True)
    out_js.parent.mkdir(parents=True, exist_ok=True)
    out_md.write_text(render_md(payload), encoding="utf-8")
    out_js.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")
    mism = sum(1 for r in matrix_on if r["verdict"] == "MISMATCH")
    print(json.dumps({"status": "PASS", "filters": len(static["servletFilters"]),
                      "chains": len(static["securityFilterChains"]),
                      "ownerCandidates": sum(1 for c in static["ownerCheckCandidates"] if c["file"]),
                      "mismatches_demo_on": mism,
                      "live": payload["live"]["status"],
                      "md": str(out_md), "json": str(out_js)}, ensure_ascii=False))
    return rc


if __name__ == "__main__":
    sys.exit(main())
