"""trace_dock_cost_guard unittest — F09 비용 함정 결합 fixture.

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.8.
"""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("trace_dock_cost_guard.py")
SPEC = importlib.util.spec_from_file_location("trace_dock_cost_guard",
                                              SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("trace_dock_cost_guard", MOD)
    SPEC.loader.exec_module(MOD)

COUPLED_TRACE = """
(function () {
  function enabled() {
    return Boolean(document.querySelector("[data-admin-diagnostics]") &&
      document.querySelector("[data-chat-trace-toggle]")?.checked);
  }
  function withDebugQuery(path) {
    const url = String(path || "");
    if (!enabled() || /(?:[?&])debug=/.test(url)) return url;
    return url + (url.includes("?") ? "&" : "?") + "debug=true";
  }
  window.AwxChatTraceUi = { withDebugQuery };
})();
"""

DECOUPLED_TRACE = """
(function () {
  function enabled() {
    return Boolean(document.querySelector("[data-admin-diagnostics]") &&
      document.querySelector("[data-chat-trace-toggle]")?.checked);
  }
  function debugRequested() {
    return Boolean(document.querySelector("[data-explicit-debug]")?.checked);
  }
  function withDebugQuery(path) {
    const url = String(path || "");
    if (!debugRequested() || /(?:[?&])debug=/.test(url)) return url;
    return url + (url.includes("?") ? "&" : "?") + "debug=true";
  }
  window.AwxChatTraceUi = { withDebugQuery };
})();
"""

CHAT_ROUTED = """
function chatTraceRequestUrl(url) {
  return window.AwxChatTraceUi?.withDebugQuery(url) || url;
}
const streamUrl = chatTraceRequestUrl("/api/chat/stream");
"""

CHAT_CLEAN = """
const streamUrl = "/api/chat/stream";
"""

CHAT_DOCK_DIAG = """
function markChatDiagnosticNode(node) { node.setAttribute("aria-hidden", "true"); }
const dock = document.querySelector("[data-testid='trace-dock-history']");
markChatDiagnosticNode(dock);
"""


class CostGuardTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.js = self.root / "main/resources/static/js"
        self.js.mkdir(parents=True)

    def write(self, trace=COUPLED_TRACE, chat=CHAT_ROUTED):
        (self.js / "chat-trace-ui.js").write_text(trace, encoding="utf-8")
        (self.js / "chat.js").write_text(chat, encoding="utf-8")

    def run_main(self, *argv):
        return MOD.main(list(argv))

    def payload(self):
        return json.loads(
            (self.root / "data/diagnostics/f01b-trace-access-0929/"
             "trace_dock_cost_guard.json").read_text(encoding="utf-8"))

    def test_coupled_fail(self):
        self.write()
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 2)
        p = self.payload()
        self.assertEqual(p["verdict"], "FAIL")
        kinds = {f["kind"] for f in p["findings"]}
        self.assertIn("VISIBLE_ON_FORCES_DEBUG_TRUE", kinds)
        self.assertIn("GENERATION_URL_VIA_DEBUG_QUERY", kinds)

    def test_baseline_allow_fail_exit_0(self):
        self.write()
        code = self.run_main("--root", str(self.root),
                             "--baseline-allow-fail")
        self.assertEqual(code, 0)
        p = self.payload()
        self.assertEqual(p["verdict"], "FAIL")
        self.assertEqual(p["baselineNote"], "expected_pre_patch")

    def test_decoupled_pass(self):
        self.write(trace=DECOUPLED_TRACE, chat=CHAT_CLEAN)
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 0)
        self.assertEqual(self.payload()["verdict"], "PASS")

    def test_dock_via_diagnostic_node_fail(self):
        self.write(trace=DECOUPLED_TRACE, chat=CHAT_DOCK_DIAG)
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 2)
        kinds = {f["kind"] for f in self.payload()["findings"]}
        self.assertIn("DOCK_ROUTED_VIA_DIAGNOSTIC_NODE", kinds)

    def test_missing_sources_partial(self):
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 3)
        self.assertEqual(self.payload()["verdict"], "PARTIAL")




DOCK_OK = """
const ids = ["trace-dock-toggle", "trace-dock-current", "trace-dock-history"];
fetch("/api/diagnostics/debug/events/page?limit=50");
document.addEventListener("visibilitychange", function () {});
if (status === 410) { cursorGap(); }
const hashRe = /^hash:[0-9a-f]{12}$/;
"""

DOCK_BAD = DOCK_OK + "\nnew EventSource('/api/diagnostics/debug/events/stream');\n"


class DockContractTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.js = self.root / "main/resources/static/js"
        self.js.mkdir(parents=True)

    def run_main(self, *argv):
        return MOD.main(list(argv))

    def payload(self):
        return json.loads(
            (self.root / "data/diagnostics/f01b-trace-access-0929/"
             "trace_dock_cost_guard.json").read_text(encoding="utf-8"))

    def test_dock_contract_absent_exit_3(self):
        (self.js / "chat.js").write_text('const u = "/api/chat/stream";\n',
                                         encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--dock-contract")
        self.assertEqual(code, 3)
        self.assertEqual(self.payload()["reason"], "SELECTORS_ABSENT_PRE_PATCH")

    def test_dock_contract_event_source_exit_2(self):
        (self.js / "dock.js").write_text(DOCK_BAD, encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--dock-contract")
        self.assertEqual(code, 2)
        kinds = {item["kind"] for item in self.payload()["findings"]}
        self.assertIn("EVENT_SOURCE", kinds)

    def test_dock_contract_compliant_exit_0(self):
        (self.js / "dock.js").write_text(DOCK_OK, encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--dock-contract")
        self.assertEqual(code, 0)
        self.assertEqual(self.payload()["verdict"], "PASS")

    def test_dock_contract_far_chat_stream_passes(self):
        (self.js / "dock.js").write_text(DOCK_OK, encoding="utf-8")
        chat = ("const id = 'trace-dock-toggle';\n" + ("\n" * 20)
                + 'const u = "/api/chat/stream";\nconst s = "/api/chat/sync";\n')
        (self.js / "chat.js").write_text(chat, encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--dock-contract")
        self.assertEqual(code, 0)



if __name__ == "__main__":
    unittest.main()
