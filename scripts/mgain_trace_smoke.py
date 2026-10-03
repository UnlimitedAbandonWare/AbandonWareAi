#!/usr/bin/env python3
"""mgain debug-trace restore -- read-only static smoke + live anchor map.

Assist artifact for the Codex task `mgain-trace-restore-0926`.
SSOT: %USERPROFILE%/Downloads/MGAIN_DEBUG_TRACE_RESTORE_2026-09-26/

What this tool does:
  * re-derives live anchor line numbers (anchors rot; never paste stale ones)
  * reports presence/absence of expected restore artifacts (R-checks)
  * flags safety-guard regressions (G-checks): permitAll on diagnostics,
    WEB_TRACE_EXPOSE default flip, ungated trace emit, legacy script
    re-execution, shared-parent [data-role="trace"] lookup, eval/new Function

What this tool is NOT:
  * not a browser/SSE/auth test and not the acceptance gate; T01-T16 live in
    agent-prompts/mgain-debug-trace-devin-assist-20260926/
    t01-t16-verification-checklist.md
  * it never writes product files

Status values:
  PASS      expectation met
  ABSENT    restore artifact not yet observed (expected before the patch)
  FAIL      guard violated (forbidden pattern present / required guard gone)
  INFO      anchor line listing

Usage:
  python -B scripts/mgain_trace_smoke.py            # advisory (pre/during patch)
  python -B scripts/mgain_trace_smoke.py --strict   # post-patch: ABSENT -> exit 1
  python -B scripts/mgain_trace_smoke.py --anchors-only
  python -B scripts/mgain_trace_smoke.py --json
Exit codes: 0 clean | 1 strict-mode unmet restore items | 2 guard FAIL | 3 error.
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

CHAT_JS = "main/resources/static/js/chat.js"
TRACE_UI_JS = "main/resources/static/js/chat-trace-ui.js"
TRACE_CSS = "main/resources/static/css/chat-trace.css"
CHAT_UI_HTML = "main/resources/templates/chat-ui.html"
PAGE_CONTROLLER = "main/java/com/example/lms/web/PageController.java"
CHAT_UI_VIEW_CONFIG = "main/java/com/example/lms/config/ChatUiViewConfig.java"
CHAT_API_CONTROLLER = "main/java/com/example/lms/api/ChatApiController.java"
TRACE_HTML_BUILDER = "main/java/com/example/lms/service/trace/TraceHtmlBuilder.java"
SNAPSHOTS_CTRL = "main/java/com/example/lms/api/TraceSnapshotsDiagnosticsController.java"
APP_SECURITY = "main/java/com/example/lms/config/AppSecurityConfig.java"
CHAT_OPEN_SECURITY = "main/java/com/example/lms/security/ChatOpenSecurityConfig.java"
APP_YML = "main/resources/application.yml"
CHAT_STREAM_EVENT = "main/java/com/example/lms/dto/ChatStreamEvent.java"


def read_lines(rel):
    path = ROOT / rel
    if not path.is_file():
        return None
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def first_match(lines, pattern):
    rx = re.compile(pattern)
    for i, line in enumerate(lines or [], 1):
        if rx.search(line):
            return i, line.strip()
    return None


def all_matches(lines, pattern, cap=8):
    rx = re.compile(pattern)
    out = []
    for i, line in enumerate(lines or [], 1):
        if rx.search(line):
            out.append((i, line.strip()))
            if len(out) >= cap:
                break
    return out


ANCHORS = [
    (CHAT_JS, "appendMessage (shared transcript parent)", r"function appendMessage\("),
    (CHAT_JS, "replaceWithSanitizedHtml (literal-dump site)", r"function replaceWithSanitizedHtml\("),
    (CHAT_JS, "textContent dump pattern", r"\.textContent\s*=\s*cleanHtml"),
    (CHAT_JS, "renderTraceHtml", r"function renderTraceHtml\("),
    (CHAT_JS, "SSE type trace dispatch", r'type\s*===\s*"trace"'),
    (CHAT_JS, "SSE type trace_html dispatch", r'type\s*===\s*"trace_html"'),
    (CHAT_JS, "streamUrl build", r"/api/chat/stream"),
    (CHAT_JS, "validateTurnTraces", r"function validateTurnTraces\("),
    (CHAT_JS, "renderRestoredTurnTrace", r"function renderRestoredTurnTrace\("),
    (CHAT_JS, "markChatDiagnosticNode", r"function markChatDiagnosticNode\("),
    (CHAT_JS, "createSseEventParser", r"function createSseEventParser\("),
    (CHAT_JS, "decodeSseEvent", r"function decodeSseEvent\("),
    (CHAT_UI_HTML, "dompurify script tag", r"dompurify"),
    (CHAT_UI_HTML, "chat.js script tag + cache key", r"/js/chat\.js"),
    (CHAT_UI_HTML, "admin diagnostics gate", r"data-admin-diagnostics"),
    (CHAT_UI_HTML, "chat-trace-ui.js loaded", r"chat-trace-ui\.js"),
    (CHAT_UI_HTML, "chat-trace.css loaded", r"chat-trace\.css"),
    (PAGE_CONTROLLER, "chatDiagnosticsEnabled/isAdmin", r"chatDiagnosticsEnabled|isAdmin\(auth\)"),
    (CHAT_UI_VIEW_CONFIG, "Jsoup projection removes admin nodes", r"data-admin-diagnostics"),
    (CHAT_UI_VIEW_CONFIG, "th:* strip loop", r'startsWith\("th:"\)'),
    (CHAT_API_CONTROLLER, "stream debug @RequestParam", r'@RequestParam\(name\s*=\s*"debug"'),
    (CHAT_API_CONTROLLER, "debug || exposeTrace emit gate", r"debug\s*\|\|\s*exposeTrace"),
    (CHAT_API_CONTROLLER, "rawTrace != null guard", r"rawTrace\s*!=\s*null"),
    (CHAT_API_CONTROLLER, "buildSplitPanel call", r"buildSplitPanel"),
    (TRACE_HTML_BUILDER, "rawTrace == null early path", r"rawTrace\s*==\s*null"),
    (TRACE_HTML_BUILDER, "renderRawSearchPanel", r"renderRawSearchPanel"),
    (TRACE_HTML_BUILDER, "renderOrchestrationPanel", r"renderOrchestrationPanel"),
    (SNAPSHOTS_CTRL, "snapshot html endpoint", r"snapshots/\{id\}/html"),
    (SNAPSHOTS_CTRL, "latest-* snapshot routes", r"latest-[a-z-]+/html"),
    (APP_SECURITY, "diagnostics ADMIN rule", r'/api/diagnostics/\*\*'),
    (CHAT_OPEN_SECURITY, "chat open chain permitAll", r'"/api/chat/\*\*"'),
    (APP_YML, "web.trace.expose default", r"WEB_TRACE_EXPOSE"),
    (CHAT_STREAM_EVENT, "ChatStreamEvent.trace factories", r"ChatStreamEvent\s+trace\("),
]


def anchor_map():
    rows = []
    for rel, label, pattern in ANCHORS:
        lines = read_lines(rel)
        if lines is None:
            rows.append({"file": rel, "label": label, "status": "MISSING_FILE"})
            continue
        hit = first_match(lines, pattern)
        if hit:
            rows.append({"file": rel, "label": label, "line": hit[0], "text": hit[1][:140]})
        else:
            rows.append({"file": rel, "label": label, "status": "NOT_FOUND"})
    return rows


def run_checks():
    results = []

    def add(cid, status, evidence):
        results.append({"id": cid, "status": status, "evidence": evidence})

    def ev(rel, hits):
        if not hits:
            return []
        if isinstance(hits, tuple):
            hits = [hits]
        return [f"{rel}:{n}: {t[:120]}" for n, t in hits]

    ui_lines = read_lines(TRACE_UI_JS)
    css_lines = read_lines(TRACE_CSS)
    chat_lines = read_lines(CHAT_JS) or []
    html_lines = read_lines(CHAT_UI_HTML) or []
    builder_lines = read_lines(TRACE_HTML_BUILDER) or []
    api_lines = read_lines(CHAT_API_CONTROLLER) or []
    sec_lines = read_lines(APP_SECURITY) or []
    yml_lines = read_lines(APP_YML) or []

    # ---- R: restore artifacts ----
    if ui_lines is None:
        add("R1.trace-ui-module", "ABSENT", [f"{TRACE_UI_JS} not created"])
    else:
        weak = first_match(ui_lines, r"\bWeakMap\b")
        pur = first_match(ui_lines, r"DOMPurify|sanitize\(")
        frag = first_match(ui_lines, r"RETURN_DOM_FRAGMENT|replaceChildren")
        upsert = first_match(ui_lines, r"upsert|AwxChatTraceUi")
        missing = [k for k, v in (("WeakMap", weak), ("DOMPurify", pur),
                                 ("RETURN_DOM_FRAGMENT/replaceChildren", frag),
                                 ("upsert/AwxChatTraceUi", upsert)) if not v]
        if missing:
            add("R1.trace-ui-module", "FAIL", [f"present but missing: {', '.join(missing)}"])
        else:
            add("R1.trace-ui-module", "PASS",
                ev(TRACE_UI_JS, [weak, pur, frag, upsert]))

    if css_lines is None:
        add("R2.trace-css", "ABSENT", [f"{TRACE_CSS} not created"])
    else:
        scope = first_match(css_lines, r"awx-trace|data-role|search-trace|trace")
        add("R2.trace-css", "PASS" if scope else "FAIL", ev(TRACE_CSS, scope) or ["no trace-scoped selector found"])

    js_ref = first_match(html_lines, r"chat-trace-ui\.js")
    css_ref = first_match(html_lines, r"chat-trace\.css")
    add("R3.template-loading", "PASS" if (js_ref and css_ref) else "ABSENT",
        ev(CHAT_UI_HTML, [h for h in (js_ref, css_ref) if h]) or ["chat-ui.html does not load chat-trace-ui.js / chat-trace.css"])

    # debug must reach the *stream request* (query param per directive 4.2).
    # Page-URL `?debug=` comments / isChatTransitionDebugEnabled / the debug
    # cockpit are different features: only lines within +-4 of a
    # `/api/chat/stream` reference, or a non-comment `[?&]debug=`/`debug=true`
    # literal, count as propagation evidence.
    # R4: debug=true must ride on every request whose response can carry trace
    # data. Two legitimate shapes exist: an inline `debug=true` literal, or a
    # helper that appends it (chat.js `chatTraceRequestUrl()` -> AwxChatTraceUi
    # .withDebugQuery). The helper form is preferred (single decision point), so
    # a URL built through it must PASS even though no literal is near the URL.
    helper_hit = first_match(chat_lines, r"function\s+[A-Za-z0-9_$]*[Tt]raceRequestUrl\s*\(")
    helper_name = ""
    if helper_hit:
        named = re.search(r"function\s+([A-Za-z0-9_$]*)", helper_hit[1])
        helper_name = named.group(1) if named else ""
    helper_body = "\n".join(chat_lines[helper_hit[0] - 1:helper_hit[0] + 6]) if helper_hit else ""
    helper_delegates = bool(helper_name) and "withDebugQuery" in helper_body
    call_sites = all_matches(chat_lines, r"\b" + re.escape(helper_name) + r"\s*\(") if helper_name else []
    call_sites = [h for h in call_sites if "function " not in h[1]]
    stream_hits = all_matches(chat_lines, r"api/chat/(stream|state|sessions)")
    code_lines = [(i, l) for i, l in enumerate(chat_lines, 1)
                  if not l.strip().startswith("//")]
    stream_win = set()
    for n, _ in stream_hits:
        stream_win.update(range(max(1, n - 4), n + 3))
    debug_stream = [h for h in code_lines
                    if h[0] in stream_win and re.search(r"debug", h[1], re.I)]
    debug_stream += [h for h in code_lines
                     if re.search(r"[?&]debug\s*=", h[1]) and h[0] not in stream_win]
    if helper_delegates and len(call_sites) >= 2:
        add("R4.debug-query-propagation", "PASS",
            [f"propagation is helper-mediated: {helper_name}() -> AwxChatTraceUi.withDebugQuery "
             f"(single decision point, {len(call_sites)} request sites wrapped)"] +
            ev(CHAT_JS, [helper_hit] + call_sites[:4]))
    elif debug_stream:
        add("R4.debug-query-propagation", "PASS", ev(CHAT_JS, debug_stream[:5]))
    else:
        add("R4.debug-query-propagation", "ABSENT",
            ev(CHAT_JS, stream_hits) or ["no trace-request helper and no debug literal on request URLs"])


    weak_any = first_match(ui_lines or [], r"\bWeakMap\b") or first_match(chat_lines, r"\bWeakMap\b.*trace|trace.*\bWeakMap\b")
    add("R5.per-assistant-ownership", "PASS" if weak_any else "ABSENT",
        (ev(TRACE_UI_JS, weak_any) if ui_lines else []) or ev(CHAT_JS, weak_any)
        or ["no WeakMap keyed by assistant node found"])

    null_guard = first_match(builder_lines, r"rawTrace\s*==\s*null")
    if null_guard is None:
        add("R6.rawTrace-null-section", "PASS", ["no rawTrace==null early return; verify B/C sections manually"])
    else:
        # PASS when the early return fires only for a genuinely-empty trace
        # (condition also requires snippets/topK/extraMeta absent) or was
        # removed; ABSENT when rawTrace==null alone still returns "".
        follow = builder_lines[null_guard[0] - 1:null_guard[0] + 6]
        cond = "\n".join(follow)
        has_empty_return = any(re.search(r'return\s*""\s*;', ln) for ln in follow)
        narrowed = bool(re.search(r"rawTrace\s*==\s*null.{0,400}(&&|\|\|)", cond, re.S))
        status = "PASS" if (not has_empty_return or narrowed) else "ABSENT"
        add("R6.rawTrace-null-section", status,
            [f"{TRACE_HTML_BUILDER}:{null_guard[0]}: {null_guard[1]}"] +
            [f"  {n}: {t.strip()[:120]}" for n, t in enumerate(follow[1:], null_guard[0] + 1)] +
            (["early return now narrowed to all-empty input -- OK"] if narrowed and has_empty_return else
             ["rawTrace==null branch no longer returns bare empty string"] if not has_empty_return else
             ["rawTrace==null still returns empty string -- Web-null/Vector-only panel missing"]))

    keep_restore = [first_match(chat_lines, r"function renderRestoredTurnTrace\("),
                    first_match(chat_lines, r"function validateTurnTraces\("),
                    first_match(chat_lines, r"turnTracesByTurnId")]
    if all(keep_restore):
        add("R7.turnTraces-restore-kept", "PASS", ev(CHAT_JS, keep_restore))
    else:
        add("R7.turnTraces-restore-kept", "FAIL", ["turnTraces restore helpers removed -- regression"])

    # R8: E01 root cause. The bubble used to receive trace HTML through
    # .textContent, so the markup rendered as literal text and zero <details>
    # panels appeared. The answer funnel must delegate to the sanitizing
    # renderer and must never assign raw markup into the bubble itself.
    funnel = first_match(chat_lines, r"function replaceWithSanitizedHtml\(")
    if funnel is None:
        add("R8.bubble-html-funnel", "FAIL",
            ["replaceWithSanitizedHtml() is gone -- re-check how trace HTML reaches the DOM"])
    else:
        body = chat_lines[funnel[0] - 1:funnel[0] + 9]
        body_text = "\n".join(body)
        delegates = "AwxChatTraceUi" in body_text
        raw_dump = bool(re.search(
            r"\.(textContent|innerHTML)\s*=\s*(cleanHtml|html|rawHtml|payload\.html)\b", body_text))
        add("R8.bubble-html-funnel", "PASS" if (delegates and not raw_dump) else "FAIL",
            ev(CHAT_JS, [funnel]) +
            [f"  delegates to AwxChatTraceUi={delegates}; raw markup assigned={raw_dump}"] +
            [f"  {n}: {t.strip()[:120]}" for n, t in enumerate(body, funnel[0])
             if re.search(r"\.(textContent|innerHTML)\s*=", t)])

    # R9: the ON/OFF control is a permission surface, not a preference. It must
    # live inside the server-rendered admin disclosure (Thymeleaf th:if gate +
    # Jsoup projection), otherwise a non-admin could reveal their own trace.
    gate = first_match(html_lines, r"data-admin-diagnostics")
    toggle = first_match(html_lines, r"data-chat-trace-toggle")
    if gate is None:
        add("R9.admin-owned-toggle-markup", "FAIL",
            ["chat-ui.html has no [data-admin-diagnostics] gate element"])
    elif toggle is None:
        add("R9.admin-owned-toggle-markup", "ABSENT",
            ["no [data-chat-trace-toggle] control -- client cannot enable trace"])
    else:
        between = toggle[0] - gate[0]
        gated = bool(re.search(r"th:if", gate[1])) and 0 <= between <= 40
        add("R9.admin-owned-toggle-markup", "PASS" if gated else "FAIL",
            ev(CHAT_UI_HTML, [gate, toggle]) +
            [f"  toggle is {between} line(s) after the gate; gate carries th:if="
             f"{bool(re.search('th:if', gate[1]))}"])

    # R10: the lazy detail fetch must keep the snapshot id inside one path
    # segment -- validated shape plus URL encoding -- so a stored id can never
    # become a path-traversal or SSRF-style pointer.
    snap_shape = first_match(ui_lines or [], r"SNAPSHOT_ID\s*=\s*/|snapshotIdShape\s*=\s*/")
    encode = first_match(ui_lines or [], r"encodeURIComponent\(")
    fetch_site = first_match(ui_lines or [], r"/api/diagnostics/trace/snapshots/")
    if not ui_lines:
        add("R10.snapshot-id-containment", "ABSENT", ["chat-trace-ui.js not present yet"])
    else:
        ok = bool(encode) and bool(fetch_site) and abs(encode[0] - fetch_site[0]) <= 6
        add("R10.snapshot-id-containment", "PASS" if ok else "FAIL",
            (ev(TRACE_UI_JS, [snap_shape]) if snap_shape else ["  no id shape validation found"]) +
            ev(TRACE_UI_JS, [h for h in (encode, fetch_site) if h]) +
            [f"  encoded segment adjacent to fetch: {ok}"])

    # ---- G: guards (must never regress) ----
    if any(re.search(r"WEB_TRACE_EXPOSE\s*:\s*true|web\.trace\.expose:\s*true", ln) for ln in yml_lines):
        add("G1.expose-default", "FAIL", ev(APP_YML, all_matches(yml_lines, r"WEB_TRACE_EXPOSE|web\.trace\.expose")))
    else:
        add("G1.expose-default", "PASS", ev(APP_YML, first_match(yml_lines, r"WEB_TRACE_EXPOSE:false")) or
            ["WEB_TRACE_EXPOSE default not found as true (verify application.yml manually if renamed)"])

    diag_admin = all_matches(sec_lines, r'/api/diagnostics/\*\*')
    diag_permit = all_matches(sec_lines, r'permitAll.*diagnostics|diagnostics.*permitAll')
    if diag_permit:
        add("G2.diagnostics-admin", "FAIL", ev(APP_SECURITY, diag_permit))
    elif diag_admin:
        add("G2.diagnostics-admin", "PASS", ev(APP_SECURITY, diag_admin))
    else:
        add("G2.diagnostics-admin", "FAIL", ["no /api/diagnostics/** rule found in AppSecurityConfig"])

    gate = first_match(api_lines, r"debug\s*\|\|\s*exposeTrace")
    add("G3.server-emit-gate", "PASS" if gate else "FAIL",
        ev(CHAT_API_CONTROLLER, gate) or ["no `debug || exposeTrace` emit gate -- check for unconditional traceHtml emit"])

    forbidden = []
    for rel, lines in ((CHAT_JS, chat_lines), (TRACE_UI_JS, ui_lines or [])):
        forbidden += [(rel, h) for h in all_matches(lines, r"data-trace-script|eval\(|new Function\(")]
    add("G4.no-dynamic-script-exec", "FAIL" if forbidden else "PASS",
        [f"{r}:{h[0]}: {h[1][:110]}" for r, h in forbidden] or ["no data-trace-script/eval/new Function"])

    # G5: the legacy defect was choosing WHICH panel to overwrite by scanning a
    # shared parent, so two answers under one transcript parent clobbered each
    # other. Sweeping every [data-role="trace"] node is legitimate when each hit
    # is resolved back through the WeakMap (dispose / toggle-off clear), so only
    # a lookup that becomes a write target is a violation. Evidence must carry
    # the file the match actually came from -- a mislabeled file name here once
    # pointed the reviewer at the wrong file for a whole debug cycle.
    lookup_rx = r"querySelector(?:All)?\([^)]*data-role[^)]*[\"']trace[\"']"
    assign_rx = (r"(?:let|const|var)?\s*([A-Za-z0-9_$]+)\s*=\s*[^;\n]*"
                 r"querySelector(?:All)?\([^)]*data-role[^)]*[\"']trace[\"']")
    offenders, sweeps = [], []
    for rel, lines in ((CHAT_JS, chat_lines), (TRACE_UI_JS, ui_lines or [])):
        weakmap_present = any(re.search(r"\bWeakMap\b", ln) for ln in lines)
        for n, line in all_matches(lines, lookup_rx, cap=12):
            assigned = re.search(assign_rx, line)
            writes_target = False
            if assigned:
                var = assigned.group(1)
                nearby = lines[n - 1:n + 14]
                writes_target = any(re.search(
                    r"\b" + re.escape(var) + r"\b\s*(\.innerHTML\s*=|\.replaceChildren\(|"
                    r"\.appendChild\(|\.textContent\s*=)", ln) for ln in nearby)
            ownership_resolved = weakmap_present and re.search(
                r"\.get\(", "\n".join(lines[n - 1:n + 6]))
            if assigned and writes_target:
                offenders.append((rel, n, line, "lookup result is written to"))
            elif ownership_resolved:
                sweeps.append((rel, n, line))
            else:
                offenders.append((rel, n, line, "lookup without WeakMap ownership resolution"))
    add("G5.no-shared-parent-lookup", "FAIL" if offenders else "PASS",
        [f"{r}:{n}: {t[:100]} -- {why}" for r, n, t, why in offenders] or
        ["no panel-selection by shared-parent scan"] +
        [f"  allowed sweep (WeakMap-resolved): {r}:{n}: {t[:90]}" for r, n, t in sweeps])


    ui_inner = all_matches(ui_lines or [], r"\.innerHTML\s*=")
    add("G6.ui-fragment-only", "PASS" if not ui_inner else "ABSENT",
        ev(TRACE_UI_JS, ui_inner) or ["no raw innerHTML writes in chat-trace-ui.js"])

    return results


def main():
    ap = argparse.ArgumentParser(description="mgain debug-trace restore read-only static smoke")
    ap.add_argument("--strict", action="store_true", help="ABSENT restore items also fail (post-patch acceptance)")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--anchors-only", action="store_true")
    args = ap.parse_args()

    anchors = anchor_map()
    if args.anchors_only:
        for a in anchors:
            loc = f"{a['file']}:{a.get('line', a.get('status', '?'))}"
            print(f"{a['label']:<46} {loc}")
        return 0

    checks = run_checks()
    guard_fails = [c for c in checks if c["status"] == "FAIL"]
    absent = [c for c in checks if c["status"] == "ABSENT"]

    if args.json:
        print(json.dumps({"root": str(ROOT), "anchors": anchors, "checks": checks,
                          "guardFails": len(guard_fails), "absent": len(absent)},
                         indent=2, ensure_ascii=False))
    else:
        print("== anchors (live) ==")
        for a in anchors:
            loc = f"{a['file']}:{a.get('line', a.get('status', '?'))}"
            print(f"  {a['label']:<46} {loc}")
        print("== checks ==")
        for c in checks:
            print(f"  [{c['status']:<6}] {c['id']}")
            for e in c["evidence"]:
                print(f"           {e}")
        print(f"== summary: {len(checks)} checks | FAIL={len(guard_fails)} ABSENT={len(absent)} ==")

    if guard_fails:
        return 2
    if args.strict and absent:
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # tool-level error is distinct from check FAIL
        print(f"mgain_trace_smoke error: {exc}", file=sys.stderr)
        sys.exit(3)
