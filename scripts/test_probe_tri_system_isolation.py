#!/usr/bin/env python3
"""Offline unit test for probe_tri_system_isolation.py (stdlib only).

Builds synthetic repo fixtures in a temp dir:
  GREEN = all hard invariants hold            -> exit 0 (warnings allowed)
  RED   = chat.js contains a display marker   -> exit 3
  IND   = StandardPromptBuilder.java missing  -> exit 2
  SIM   = --simulate-requests on an empty root -> exit 0, defect warning

Exits 0 with 'ALL PASS' on success, 1 otherwise. Never touches the live tree.
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROBE = ROOT / "scripts" / "probe_tri_system_isolation.py"

RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(("PASS" if cond else "FAIL"), name, detail)


SPB = """package com.example.lms.prompt;
public class StandardPromptBuilder {
    public String buildInstructions(PromptContext ctx) {
        boolean focusOutput = ctx != null && ctx.focusAnswerLengthChars() != null;
        StringBuilder sb = new StringBuilder();
        if (ctx != null && !focusOutput && ctx.sectionSpec() != null) {
            sb.append("\\n### SECTION TEMPLATE\\n");
        }
        if (focusOutput) {
            sb.append("### DISPLAY FOCUS OUTPUT\\n");
        }
        if (!focusOutput && minWords != null) sb.append("- minimum words: ");
        return sb.toString();
    }
}
"""
CWF = """package com.example.lms.service;
public class ChatWorkflow {
    void x() {
        ctxBuilder.focusAnswerLengthChars(conversationContext.focusAnswerLengthChars());
    }
}
"""
NFS = """package com.example.lms.assist;
public class NovaFocusAnswerService {
    void x() {
        requestBuilder.useRag(false).useWebSearch(web).webTopK(imagePresent||quick?0:3);
    }
}
"""
PCTX = """package com.example.lms.prompt;
public class PromptContext {
    void v() { throw new IllegalArgumentException("invalid_focus_answer_length"); }
}
"""
CCC = """package com.example.lms.service;
public class ChatConversationContext {
    void v() { throw new IllegalArgumentException("invalid_focus_answer_length"); }
}
"""
NFSSET = """package com.example.lms.assist;
public class NovaFocusSettings { public enum ExecutionTarget { AUTO,API_ONLY,LOCAL_ONLY } }
"""
NFSTEST = """package com.example.lms.assist;
class NovaFocusDisplayContractTest {
    void sharedPromptBoundaryHonorsFocusLengthAndLeavesOrdinaryChatAlone() {}
}
"""
CHAT_JS_CLEAN = "// chat controller\nlet sessionListRefreshGeneration = 0;\n"
CHAT_JS_DIRTY = CHAT_JS_CLEAN + "loadScript('assets/display/receiver.js');\n"
FOCUS_JS = "// focus controls\nconst f = await host.fetch('/api/chat/models');\n"
INT_JS = "// interview debug\nvar s = win.sessionStorage;\n"
CHAT_HTML = '<html><head><script defer src="/js/chat.js?v=x"></script></head></html>'
BRIDGE_JS = "// bridge\nfunction resolveEnabled() {} /* displayBridge */\n"
DOC = "# doc\n"


def make_root(tmp, chat_js=CHAT_JS_CLEAN, spb=SPB):
    root = Path(tmp)
    j = root / "main/java/com/example/lms"
    for rel, text in (
            ("prompt/StandardPromptBuilder.java", spb),
            ("prompt/PromptContext.java", PCTX),
            ("service/ChatWorkflow.java", CWF),
            ("service/ChatConversationContext.java", CCC),
            ("assist/NovaFocusAnswerService.java", NFS),
            ("assist/NovaFocusSettings.java", NFSSET)):
        if text is None:
            continue
        (j / rel).parent.mkdir(parents=True, exist_ok=True)
        (j / rel).write_text(text, encoding="utf-8")
    t = root / "src/test/java/com/example/lms/assist"
    t.mkdir(parents=True)
    (t / "NovaFocusDisplayContractTest.java").write_text(NFSTEST, "utf-8")
    js = root / "main/resources/static/js"
    js.mkdir(parents=True)
    (js / "chat.js").write_text(chat_js, "utf-8")
    (js / "chat-display-bridge.js").write_text(BRIDGE_JS, "utf-8")
    disp = root / "main/resources/static/assets/display"
    disp.mkdir(parents=True)
    (disp / "display-focus-controls.js").write_text(FOCUS_JS, "utf-8")
    iv = root / "main/resources/static/assets/interview"
    iv.mkdir(parents=True)
    (iv / "app.js").write_text(INT_JS, "utf-8")
    tpl = root / "main/resources/templates"
    tpl.mkdir(parents=True)
    (tpl / "chat-ui.html").write_text(CHAT_HTML, "utf-8")
    for rel in ("docs/design/TRI_SYSTEM_ISOLATION_SPEC.md",
                "docs/agents-rules/DEMO1-TRI-SYSTEM-SEAM-ISOLATION.md",
                "docs/PRIMARY_SURFACE.md"):
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_text(DOC, "utf-8")
    return root


def run_probe(*args):
    return subprocess.run([sys.executable, "-B", str(PROBE), *args],
                          capture_output=True, text=True, cwd=ROOT)


def main():
    with tempfile.TemporaryDirectory() as tmp:
        green = make_root(Path(tmp) / "green")
        r = run_probe("--root", str(green), "--json")
        data = json.loads(r.stdout)
        check("green exit=0", r.returncode == 0,
              "exit=%s stderr=%s" % (r.returncode, r.stderr.strip()[:200]))
        check("green assertions>=15", data["assertions"] >= 15,
              "assertions=%d" % data["assertions"])
        check("green no failed", data["failed"] == [])
        check("green enum warning reported",
              any(w["kind"] == "enum-not-landed" for w in data["warnings"]))
        check("green local-only policy noted",
              any(w["kind"] == "policy-conflict-local-only"
                  for w in data["warnings"]))

        red = make_root(Path(tmp) / "red", chat_js=CHAT_JS_DIRTY)
        r = run_probe("--root", str(red), "--json")
        data = json.loads(r.stdout)
        check("red exit=3", r.returncode == 3, "exit=%s" % r.returncode)
        check("red chatjs violation named",
              "chatjs-no-display-markers" in data["failed"],
              "failed=%s" % data["failed"])

        ind = make_root(Path(tmp) / "ind", spb=None)
        r = run_probe("--root", str(ind), "--json")
        check("indeterminate exit=2", r.returncode == 2,
              "exit=%s" % r.returncode)

        empty = Path(tmp) / "empty"
        empty.mkdir()
        r = run_probe("--root", str(empty), "--simulate-requests", "--json")
        data = json.loads(r.stdout)
        check("simulate exit=0", r.returncode == 0, "exit=%s" % r.returncode)
        check("simulate defect warning",
              any(w["kind"] == "latent-defect-reproduced"
                  for w in data["warnings"]),
              "warnings=%s" % [w["kind"] for w in data["warnings"]])

        r = run_probe("--root", str(green), "--report", "--json")
        data = json.loads(r.stdout)
        rp = green / "data/agent-handoff/tri-system-isolation/report.json"
        check("report written", rp.is_file() and
              json.loads(rp.read_text(encoding="utf-8"))["exit"] == 0,
              "path=%s" % data.get("reportPath"))

        r = run_probe("--root", str(green), "--scan-sources")
        check("scan-sources exit=0", r.returncode == 0,
              "exit=%s" % r.returncode)

    failed = [n for n, ok, _ in RESULTS if not ok]
    print("ALL PASS" if not failed else "FAILED: %s" % ", ".join(failed))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
