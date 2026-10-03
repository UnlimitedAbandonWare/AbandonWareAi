"""trace_dock_a11y_scan unittest — dock 셀렉터 숨김 속성 fixture.

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.8.
"""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("trace_dock_a11y_scan.py")
SPEC = importlib.util.spec_from_file_location("trace_dock_a11y_scan",
                                              SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("trace_dock_a11y_scan", MOD)
    SPEC.loader.exec_module(MOD)

CLEAN_HTML = """
<aside data-testid="trace-dock-history" role="complementary"
       aria-label="이전 대화의 기억·트레이스 요약"></aside>
<div data-testid="trace-dock-current"></div>
<button data-testid="trace-dock-toggle"></button>
"""

HIDDEN_HTML = """
<aside data-testid="trace-dock-history" aria-hidden="true"></aside>
"""

COLLAPSIBLE_HTML = """
<aside data-trace-dock data-testid="trace-dock">
  <button data-testid="trace-dock-toggle"></button>
  <div id="traceDockBody" hidden>
    <section data-testid="trace-dock-current"></section>
    <section data-testid="trace-dock-history"></section>
  </div>
</aside>
"""

JS_DOCK_DIAG = """
const dock = document.querySelector("[data-testid='trace-dock-current']");
markChatDiagnosticNode(dock);
"""

JS_PLAIN = """
function markChatDiagnosticNode(node) { node.setAttribute("aria-hidden", "true"); }
const card = document.createElement("div");
markChatDiagnosticNode(card);
"""


class A11yScanTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.js = self.root / "main/resources/static/js"
        self.tpl = self.root / "main/resources/templates"
        self.js.mkdir(parents=True)
        self.tpl.mkdir(parents=True)

    def run_main(self, *argv):
        return MOD.main(list(argv))

    def payload(self):
        return json.loads(
            (self.root / "data/diagnostics/f01b-trace-access-0929/"
             "trace_dock_a11y_scan.json").read_text(encoding="utf-8"))

    def test_selectors_absent_partial(self):
        (self.tpl / "chat-ui.html").write_text("<div>chat</div>",
                                             encoding="utf-8")
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 3)
        p = self.payload()
        self.assertEqual(p["verdict"], "PARTIAL")
        self.assertEqual(p["reason"], "SELECTORS_ABSENT_PRE_PATCH")
        self.assertEqual(p["browserA11ySnapshot"], "NOT_RUN")

    def test_clean_selectors_pass(self):
        (self.tpl / "chat-ui.html").write_text(CLEAN_HTML, encoding="utf-8")
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 0)
        p = self.payload()
        self.assertEqual(p["verdict"], "PASS")
        self.assertEqual(len(p["occurrences"]), 3)

    def test_hidden_selector_fail(self):
        (self.tpl / "chat-ui.html").write_text(HIDDEN_HTML, encoding="utf-8")
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 2)
        kinds = {f["kind"] for f in self.payload()["findings"]}
        self.assertIn("DOCK_ELEMENT_HIDDEN", kinds)

    def test_collapsed_body_preserves_visible_dock_and_toggle(self):
        (self.tpl / "chat-ui.html").write_text(COLLAPSIBLE_HTML, encoding="utf-8")
        self.assertEqual(self.run_main("--root", str(self.root)), 0)
        self.assertEqual(self.payload()["findings"], [])

    def test_hidden_root_toggle_and_section_still_fail(self):
        variants = [
            COLLAPSIBLE_HTML.replace("data-trace-dock", "data-trace-dock hidden", 1),
            COLLAPSIBLE_HTML.replace("data-trace-dock", 'data-trace-dock aria-hidden="true"', 1),
            COLLAPSIBLE_HTML.replace("data-trace-dock", 'data-trace-dock style="display:none"', 1),
            COLLAPSIBLE_HTML.replace('data-testid="trace-dock-toggle"',
                                     'data-testid="trace-dock-toggle" inert', 1),
            COLLAPSIBLE_HTML.replace('data-testid="trace-dock-current"',
                                     'data-testid="trace-dock-current" hidden', 1),
            COLLAPSIBLE_HTML.replace('id="traceDockBody" hidden',
                                     'id="traceDockBody" aria-hidden="true"', 1),
        ]
        for html in variants:
            with self.subTest(html=html):
                (self.tpl / "chat-ui.html").write_text(html, encoding="utf-8")
                self.assertEqual(self.run_main("--root", str(self.root)), 2)
                self.assertTrue(self.payload()["findings"])

    def test_dock_via_hidden_helper_fail(self):
        (self.js / "chat.js").write_text(JS_DOCK_DIAG, encoding="utf-8")
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 2)
        kinds = {f["kind"] for f in self.payload()["findings"]}
        self.assertIn("DOCK_VIA_HIDDEN_HELPER", kinds)

    def test_plain_diagnostic_helper_no_fail(self):
        (self.js / "chat.js").write_text(JS_PLAIN, encoding="utf-8")
        (self.tpl / "chat-ui.html").write_text(CLEAN_HTML, encoding="utf-8")
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 0)
        # --also 심볼은 정보용으로만 기록된다
        self.assertTrue(self.payload()["alsoSites"])


if __name__ == "__main__":
    unittest.main()
