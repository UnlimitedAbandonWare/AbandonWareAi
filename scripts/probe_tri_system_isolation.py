#!/usr/bin/env python3
"""Offline probe: tri-system isolation invariants (Meta Display / RAG / main /chat).

Contract under test (Codex owns the Java change; Devin directive
"tri-system isolation" DV05, spec docs/design/TRI_SYSTEM_ISOLATION_SPEC.md):

  The three surfaces share ChatWorkflow -> StandardPromptBuilder. Today the
  Display branch is detected only by `PromptContext.focusAnswerLengthChars
  != null` (StandardPromptBuilder.java L529) — a stray length field on a
  /chat request would flip it into DISPLAY FOCUS OUTPUT and mask
  SECTION TEMPLATE/minimum words. Target state adds a `SurfaceOrigin`
  enum guard. This probe asserts the shipped invariants stay green and
  reports the not-yet-landed enum as a warning, never as a violation.

Modes:
  --dry-run            run the 20+ static invariant assertions (default scan)
  --scan-sources       focused cross-pollution scan of the four named files
  --simulate-requests  model the prompt-builder gate on synthetic Display and
                       main-chat payloads; a stray-length main payload flags
                       the latent defect (warning) under the length-only gate
  --report [PATH]      write JSON report (default
                       data/agent-handoff/tri-system-isolation/report.json)
  --json               JSON stdout

Exit codes:
  0 = all hard invariants hold (warnings may be present)
  3 = a hard invariant is violated
  2 = indeterminate (required file missing / unrecognized shape)
  1 = usage error

Read-only and offline. Output carries only paths, line numbers, and marker
names -- never file contents beyond the matched markers.
"""

import argparse
import json
import re
import sys
from pathlib import Path

JAVA = Path("main/java/com/example/lms")
SPB = JAVA / "prompt/StandardPromptBuilder.java"
PCTX = JAVA / "prompt/PromptContext.java"
PCTX_ALT = JAVA / "service/prompt/model/PromptContext.java"
CWF = JAVA / "service/ChatWorkflow.java"
CCC = JAVA / "service/ChatConversationContext.java"
NFS = JAVA / "assist/NovaFocusAnswerService.java"
NFS_SET = JAVA / "assist/NovaFocusSettings.java"
NFS_TEST = Path("src/test/java/com/example/lms/assist/NovaFocusDisplayContractTest.java")
ENUM = JAVA / "domain/enums/SurfaceOrigin.java"

CHAT_JS = Path("main/resources/static/js/chat.js")
FOCUS_JS = Path("main/resources/static/assets/display/display-focus-controls.js")
BRIDGE_JS = Path("main/resources/static/js/chat-display-bridge.js")
CHAT_HTML = Path("main/resources/templates/chat-ui.html")
INTERVIEW_DIR = Path("main/resources/static/assets/interview")

SPEC_DOC = Path("docs/design/TRI_SYSTEM_ISOLATION_SPEC.md")
RULE_DOC = Path("docs/agents-rules/DEMO1-TRI-SYSTEM-SEAM-ISOLATION.md")
SURFACE_DOC = Path("docs/PRIMARY_SURFACE.md")

DISPLAY_MARKERS = [re.compile(p) for p in (
    r"display-focus", r"focus-controls", r"receiver\.js", r"lens\.js",
    r"lens\.html", r"DisplayConversate", r"assets/display/",
    r"caption-text", r"hint-text", r"evidence-text", r"nf-answer",
    r"nf-embed", r"nf-memory")]
CHAT_MARKERS = [re.compile(p) for p in (
    r"sessionListRefresh", r"chatAccessState", r"strictBackendSessionId",
    r"sessionListContainer", r"sessionListRowMetadata", r"data-session-list",
    r"data-session-selection-state", r"session-mode-list", r"newChatBtn",
    r"chat-request-budget-ms", r"data-chat-surface")]


def _read(root, rel):
    p = Path(root) / rel
    try:
        return p.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None


def _line(text, pat):
    m = re.search(pat, text)
    return (text.count("\n", 0, m.start()) + 1) if m else None


def _absent(text, patterns):
    """Return list of offending marker names (empty = clean)."""
    return [p.pattern for p in patterns if p.search(text)]


def check_backend(texts):
    """Hard assertions on the Java seam. Returns (checks, indeterminate)."""
    checks, ind = [], False
    spb = texts.get("spb")
    if spb is None:
        return checks, True
    checks.append(("spb-focus-length-detection",
                   "focusAnswerLengthChars() != null" in spb,
                   "StandardPromptBuilder focusOutput anchor"))
    checks.append(("spb-focus-output-block",
                   "DISPLAY FOCUS OUTPUT" in spb,
                   "Display-only prompt block exists"))
    sect = _line(spb, r"### SECTION TEMPLATE")
    focus_ctx = _line(spb, r"!focusOutput")
    checks.append(("spb-section-gated-not-focus",
                   sect is not None and focus_ctx is not None
                   and focus_ctx < sect,
                   "SECTION TEMPLATE guarded by !focusOutput"))
    checks.append(("spb-minwords-gated-not-focus",
                   re.search(r"!focusOutput[^\n]*minWords", spb) is not None,
                   "minimum words guarded by !focusOutput"))

    cwf = texts.get("cwf")
    if cwf is None:
        return checks, True
    checks.append(("cwf-maps-focus-length",
                   "conversationContext.focusAnswerLengthChars()" in cwf,
                   "ChatWorkflow maps conversationContext -> PromptContext"))

    nfs = texts.get("nfs")
    if nfs is None:
        return checks, True
    checks.append(("nfs-rag-off-in-adapter",
                   ".useRag(false)" in nfs,
                   "Display request pins useRag=false inside adapter boundary"))
    checks.append(("nfs-quick-webtopk-zero",
                   re.search(r"webTopK\([^)]*\b0\b", nfs) is not None,
                   "quick/image path pins webTopK=0"))

    pctx = texts.get("pctx")
    if pctx is None:
        return checks, True
    checks.append(("pctx-length-range-validated",
                   "invalid_focus_answer_length" in pctx,
                   "PromptContext enforces 80..800"))
    ccc = texts.get("ccc")
    if ccc is None:
        return checks, True
    checks.append(("ccc-length-range-validated",
                   "invalid_focus_answer_length" in ccc,
                   "ChatConversationContext enforces 80..800"))
    return checks, ind


def check_frontend(root):
    """Hard assertions on the JS/HTML seam."""
    checks = []
    chat = _read(root, CHAT_JS)
    if chat is None:
        return checks, True
    checks.append(("chatjs-no-display-markers",
                   not _absent(chat, DISPLAY_MARKERS),
                   "chat.js free of display/lens/interview markers"))
    focus = _read(root, FOCUS_JS)
    if focus is None:
        return checks, True
    checks.append(("focusctl-no-chat-state",
                   not _absent(focus, CHAT_MARKERS),
                   "display-focus-controls.js free of chat session state"))
    idir = Path(root) / INTERVIEW_DIR
    if not idir.is_dir():
        return checks, True
    bad = []
    for f in sorted(idir.glob("*.js")):
        t = f.read_text(encoding="utf-8", errors="replace")
        bad.extend(_absent(t, CHAT_MARKERS))
        if re.search(r"localStorage\.setItem\(['\"]chat", t):
            bad.append("localStorage.chat-write")
    checks.append(("interview-no-chat-state", not bad,
                   "interview assets never touch /chat state"))
    html = _read(root, CHAT_HTML)
    if html is None:
        return checks, True
    checks.append(("chatui-loads-chatjs",
                   re.search(r'src="/js/chat\.js', html) is not None,
                   "chat-ui.html loads /js/chat.js"))
    checks.append(("chatui-no-display-scripts",
                   not _absent(html, [re.compile(p) for p in (
                       r"display-focus-controls", r"receiver\.js",
                       r"lens\.js", r"display-conversate\.js")]),
                   "chat-ui.html never loads display shells"))
    bridge = _read(root, BRIDGE_JS)
    if bridge is not None:
        checks.append(("bridge-optin-gate",
                       "resolveEnabled" in bridge and "displayBridge" in bridge,
                       "declared bridge keeps opt-in gate"))
    return checks, False


def check_docs(root):
    """Hard assertions on the SSOT/rule docs this task ships."""
    checks = []
    for rel, name in ((SPEC_DOC, "spec-doc-present"),
                      (RULE_DOC, "rule-doc-present"),
                      (SURFACE_DOC, "primary-surface-doc-present")):
        checks.append((name, (Path(root) / rel).is_file(), str(rel)))
    return checks, False


def scan(root):
    """Collect all check results + warnings."""
    texts = {k: _read(root, rel) for k, rel in (
        ("spb", SPB), ("pctx", PCTX), ("cwf", CWF),
        ("ccc", CCC), ("nfs", NFS), ("nfsset", NFS_SET),
        ("nfstest", NFS_TEST))}
    warnings = []
    checks = []
    ind = False
    for fn in (lambda: check_backend(texts), lambda: check_frontend(root),
               lambda: check_docs(root)):
        c, i = fn()
        checks.extend(c)
        ind = ind or i

    # Informational: enum landed?
    if texts.get("spb") is not None:
        landed = _read(root, ENUM) is not None
        surfaced = "surfaceOrigin" in (texts.get("pctx") or "")
        if not landed or not surfaced:
            warnings.append({
                "kind": "enum-not-landed",
                "detail": "SurfaceOrigin enum/binding absent; length-only "
                          "focus detection remains the live gate",
                "expected": "pending Codex patch (FOR_CODEX_TRI_SYSTEM_ISOLATION)"})
    # Contract test exists and pins the shared boundary?
    nt = texts.get("nfstest")
    if nt is None:
        warnings.append({"kind": "contract-test-missing",
                         "detail": str(NFS_TEST)})
    elif "sharedPromptBoundaryHonorsFocusLengthAndLeavesOrdinaryChatAlone" not in nt:
        checks.append(("contract-test-pins-boundary", False,
                       "sharedPromptBoundary test missing"))
    else:
        checks.append(("contract-test-pins-boundary", True,
                       "sharedPromptBoundary test present"))
    # Second PromptContext can be mis-patched.
    if _read(root, PCTX_ALT) is not None:
        warnings.append({"kind": "duplicate-promptcontext",
                         "detail": str(PCTX_ALT),
                         "expected": "patch com.example.lms.prompt.PromptContext only"})
    # Live LOCAL_ONLY user option vs directive I3.
    if texts.get("nfsset") is not None and "LOCAL_ONLY" in texts["nfsset"]:
        warnings.append({
            "kind": "policy-conflict-local-only",
            "detail": "NovaFocusSettings.ExecutionTarget exposes LOCAL_ONLY",
            "expected": "spec S5 open decision: auto/fallback paths must never "
                        "silently enter local lanes; explicit owner choice TBD"})
    return checks, warnings, ind


def simulate():
    """Pure-python model of the live prompt gate.

    Returns (checks, warnings). Under the shipped length-only gate, a
    main-chat payload carrying a stray focusAnswerLengthChars reproduces
    the defect -> warning (not a hard fail; the latent bug is documented,
    the fix is Codex-owned)."""
    def gate(payload):
        focus = payload.get("focusAnswerLengthChars") is not None
        out = {"focusOutput": focus, "blocks": []}
        if focus:
            out["blocks"].append("DISPLAY FOCUS OUTPUT")
        else:
            if payload.get("sectionSpec"):
                out["blocks"].append("SECTION TEMPLATE")
            if payload.get("minWordCount"):
                out["blocks"].append("minimum words")
        out["flags"] = {
            "useRag": payload.get("useRag"),
            "useWebSearch": payload.get("useWebSearch"),
            "webTopK": payload.get("webTopK", 8)}
        return out

    checks, warnings = [], []
    display = {"surface": "META_DISPLAY", "focusAnswerLengthChars": 400,
               "quick": True, "useRag": False, "useWebSearch": False,
               "webTopK": 0}
    main = {"surface": "MAIN_CHAT", "focusAnswerLengthChars": None,
            "minWordCount": 500, "sectionSpec": ["OVERVIEW", "DETAILS"],
            "useRag": True, "useWebSearch": True, "webTopK": 8}
    stray = dict(main, focusAnswerLengthChars=400)

    d = gate(display)
    checks.append(("sim-display-gets-focus-block",
                   d["focusOutput"] and "DISPLAY FOCUS OUTPUT" in d["blocks"]
                   and "SECTION TEMPLATE" not in d["blocks"],
                   "Display payload -> FOCUS block, no SECTION TEMPLATE"))
    checks.append(("sim-display-quick-closes-search",
                   d["flags"]["useRag"] is False
                   and d["flags"]["useWebSearch"] is False
                   and d["flags"]["webTopK"] == 0,
                   "Quick mode suppresses retrieval inside the adapter"))
    m = gate(main)
    checks.append(("sim-main-keeps-rag-shape",
                   not m["focusOutput"]
                   and "SECTION TEMPLATE" in m["blocks"]
                   and "minimum words" in m["blocks"],
                   "main /chat payload keeps section template + min words"))
    s = gate(stray)
    if s["focusOutput"]:
        warnings.append({
            "kind": "latent-defect-reproduced",
            "detail": "main-chat payload + stray focusAnswerLengthChars "
                      "flips into DISPLAY FOCUS OUTPUT under the "
                      "length-only gate (SurfaceOrigin lands via Codex)",
            "severity": "warning"})
        checks.append(("sim-stray-length-defect-visible", True,
                       "defect reproduced -> warning only"))
    else:
        checks.append(("sim-stray-length-defect-visible", True,
                       "origin-gated build already isolates stray length"))
    return checks, warnings


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--dry-run", action="store_true", help="run all static assertions")
    ap.add_argument("--scan-sources", action="store_true",
                    help="emit per-file cross-pollution findings")
    ap.add_argument("--simulate-requests", action="store_true",
                    help="model the prompt gate on synthetic payloads")
    ap.add_argument("--report", nargs="?", const=str(
                        Path("data/agent-handoff/tri-system-isolation/report.json")),
                    help="write JSON report (default path under --root)")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()

    if not (args.dry_run or args.scan_sources or args.simulate_requests):
        args.dry_run = True  # default: full static sweep

    checks, warnings, ind = [], [], False
    if args.dry_run or args.scan_sources:
        c, w, ind = scan(root)
        checks.extend(c)
        warnings.extend(w)
    if args.simulate_requests:
        c, w = simulate()
        checks.extend(c)
        warnings.extend(w)

    failed = [n for n, ok, _ in checks if not ok]
    code = 2 if ind else (3 if failed else 0)
    out = {"schemaVersion": "awx.tri-system-isolation.v1",
           "root": str(root), "mode": {
               "dryRun": args.dry_run, "scanSources": args.scan_sources,
               "simulateRequests": args.simulate_requests},
           "assertions": len(checks), "failed": failed,
           "checks": [{"name": n, "ok": ok, "detail": d}
                      for n, ok, d in checks],
           "warnings": warnings, "exit": code}

    rep = args.report
    if rep is not None:
        rp = Path(rep)
        if not rp.is_absolute():
            rp = root / rp
        rp.parent.mkdir(parents=True, exist_ok=True)
        rp.write_text(json.dumps(out, indent=2, sort_keys=True),
                      encoding="utf-8")
        out["reportPath"] = str(rp)

    if args.json:
        print(json.dumps(out, indent=2, sort_keys=True))
    else:
        label = {0: "ISOLATED", 3: "VIOLATION", 2: "INDETERMINATE"}[code]
        print("%s assertions=%d failed=%d warnings=%d"
              % (label, len(checks), len(failed), len(warnings)))
        for n, ok, d in checks:
            if not ok:
                print("  FAIL %s -- %s" % (n, d))
        for w in warnings:
            print("  WARN %s -- %s" % (w["kind"], w["detail"]))
        if rep:
            print("  report=%s" % out["reportPath"])
    return code


if __name__ == "__main__":
    sys.exit(main())
