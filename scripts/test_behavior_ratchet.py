"""Regression tests for scripts/behavior_ratchet.py (stdlib unittest, offline)."""
import datetime as dt
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import behavior_ratchet as br  # noqa: E402

YAML_OLD = "x:\n  search-mode: 'AUTO'\nchat:\n  defaults:\n    use-rag: true\n    use-web-search: false\n    search-mode: 'OFF'\n  other: 1\n"
YAML_NEW = YAML_OLD.replace("use-web-search: false", "use-web-search: true").replace("search-mode: 'OFF'", "search-mode: 'AUTO'")
JAVA_T = ("class T {\n  @Test\n  void keepsAuto() {}\n  @Test @DisplayName(\"x; y\")\n  void explicitFalseIsOff() {}\n"
          "  @ParameterizedTest\n  @ValueSource(strings={\"a\"})\n  void merges(String s) {}\n  void helper() {}\n}\n")
JS_T = "test('defaults are AUTO', () => {});\nit(\"rag on\", () => {});\n"

CFG = {"entries": [
    {"id": "y.search", "kind": "present", "file": "app.yaml", "section": {"start": "^  defaults:\\s*$", "mode": "indent"},
     "pattern": "^\\s+search-mode:\\s*['\"]?AUTO['\"]?\\s*$", "oldPattern": "^\\s+search-mode:\\s*['\"]?OFF['\"]?\\s*$"},
    {"id": "y.web", "kind": "present", "file": "app.yaml", "section": {"start": "^  defaults:\\s*$", "mode": "indent"},
     "pattern": "^\\s+use-web-search:\\s*true\\b", "oldPattern": "^\\s+use-web-search:\\s*false\\b"},
    {"id": "enum.nostd", "kind": "absent", "files": ["Enum.java"], "pattern": "\\bSTANDARD\\b"},
    {"id": "t.java", "kind": "test_names", "lang": "java", "file": "T.java"},
    {"id": "t.js", "kind": "test_names", "lang": "js", "file": "t.test.cjs"},
]}


class RatchetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "configs").mkdir()
        self.write("configs/behavior-ratchet.json", json.dumps(CFG))
        self.write("app.yaml", YAML_OLD)
        self.write("Enum.java", "enum E { AUTO, OFF }")
        self.write("T.java", JAVA_T)
        self.write("t.test.cjs", JS_T)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, text, crlf=False):
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes((text.replace("\n", "\r\n") if crlf else text).encode("utf-8"))

    def run_cmd(self, *argv):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = br.main(["--root", str(self.root), *argv])
        out = buf.getvalue().strip().splitlines()
        return code, (json.loads(out[-1]) if out and out[-1].startswith("{") else out)

    def states(self):
        code, doc = self.run_cmd("check", "--json")
        return code, {r["id"]: r["state"] for r in doc["results"]}

    def lease(self, paths, minutes=30):
        exp = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=minutes)).isoformat()
        self.write("__patch_drop__/source-edit-locks/x/lease.json",
                   json.dumps({"expiresAtUtc": exp, "targetPaths": [p.lower() for p in paths]}))

    def test_pending_before_patch_is_not_failure(self):
        code, s = self.states()
        self.assertEqual(code, 0)
        self.assertEqual(s["y.search"], "PENDING")
        self.assertEqual(s["y.web"], "PENDING")
        self.assertEqual(s["enum.nostd"], "LANDED")

    def test_section_scope_ignores_auto_outside_defaults(self):
        # top-level x.search-mode AUTO must not count as the chat.defaults value
        code, s = self.states()
        self.assertEqual(s["y.search"], "PENDING")

    def test_land_lock_then_revert_fails_exit4(self):
        self.write("app.yaml", YAML_NEW, crlf=True)
        _, s = self.states()
        self.assertEqual(s["y.search"], "LANDED")
        code, doc = self.run_cmd("update", "--task", "t1")
        self.assertIn("y.search", doc["locked"])
        _, s = self.states()
        self.assertEqual(s["y.search"], "LOCKED")
        self.write("app.yaml", YAML_OLD)
        code, s = self.states()
        self.assertEqual(code, 4)
        self.assertEqual(s["y.search"], "REVERTED")

    def test_live_lease_reports_in_flight_and_blocks_lock(self):
        self.write("app.yaml", YAML_NEW)
        self.run_cmd("update")
        self.write("app.yaml", YAML_OLD)
        self.lease(["app.yaml"])
        code, s = self.states()
        self.assertEqual(code, 0)
        self.assertEqual(s["y.search"], "IN_FLIGHT")
        self.write("Enum.java", "enum E { AUTO }")
        lockdoc = json.loads((self.root / br.LOCK_REL).read_text(encoding="utf-8"))
        self.assertIn("y.search", lockdoc["locks"])

    def test_update_skips_leased_landed(self):
        self.write("app.yaml", YAML_NEW)
        self.lease(["APP.yaml"])
        _, doc = self.run_cmd("update")
        self.assertNotIn("y.search", doc["locked"])
        self.assertIn("y.search", doc["skippedLeased"])

    def test_expired_lease_ignored(self):
        self.write("app.yaml", YAML_NEW)
        self.run_cmd("update")
        self.write("app.yaml", YAML_OLD)
        self.lease(["app.yaml"], minutes=-5)
        code, s = self.states()
        self.assertEqual((code, s["y.search"]), (4, "REVERTED"))

    def test_absent_violation_after_lock(self):
        self.run_cmd("update")
        self.write("Enum.java", "enum E { AUTO, OFF, STANDARD }")
        code, s = self.states()
        self.assertEqual((code, s["enum.nostd"]), (4, "REVERTED"))

    def test_java_and_js_test_names(self):
        self.assertEqual(br.test_names(JAVA_T, "java"), ["explicitFalseIsOff", "keepsAuto", "merges"])
        self.assertEqual(br.test_names(JS_T, "js"), ["defaults are AUTO", "rag on"])

    def test_test_names_only_grow_and_removal_reverts(self):
        self.run_cmd("update")
        self.write("T.java", JAVA_T.replace("@Test\n  void keepsAuto() {}\n", "") + "@Test void newOne(){}")
        code, s = self.states()
        self.assertEqual((code, s["t.java"]), (4, "REVERTED"))
        self.write("T.java", JAVA_T + "class U { @Test void added() {} }")
        self.run_cmd("update")
        lock = json.loads((self.root / br.LOCK_REL).read_text(encoding="utf-8"))["locks"]["t.java"]
        self.assertIn("added", lock["names"])
        self.assertIn("keepsAuto", lock["names"])

    def test_removing_locked_entry_from_config_reverts(self):
        self.run_cmd("update")
        cfg = dict(CFG, entries=[e for e in CFG["entries"] if e["id"] != "enum.nostd"])
        self.write("configs/behavior-ratchet.json", json.dumps(cfg))
        code, s = self.states()
        self.assertEqual((code, s["enum.nostd"]), (4, "REVERTED"))

    def test_unlock_requires_user_accepted_adr(self):
        self.run_cmd("update")
        self.write("docs/architecture/decisions/ADR-9.md", "---\nstatus: PROPOSED\napprovedBy: user\nratchet: enum.nostd\n---\n")
        code, doc = self.run_cmd("unlock", "--id", "enum.nostd", "--adr", "docs/architecture/decisions/ADR-9.md")
        self.assertEqual(code, 1)
        self.write("elsewhere/ADR.md", "---\nstatus: ACCEPTED\napprovedBy: user\nratchet: enum.nostd\n---\n")
        code, doc = self.run_cmd("unlock", "--id", "enum.nostd", "--adr", "elsewhere/ADR.md")
        self.assertEqual(code, 1)
        self.write("docs/architecture/decisions/ADR-9.md", "---\nstatus: ACCEPTED\napprovedBy: user\nratchet: enum.nostd\n---\n")
        code, doc = self.run_cmd("unlock", "--id", "enum.nostd", "--adr", "docs/architecture/decisions/ADR-9.md")
        self.assertEqual(code, 0)
        hist = json.loads((self.root / br.LOCK_REL).read_text(encoding="utf-8"))["history"]
        self.assertEqual(hist[-1]["action"], "unlock")

    def test_custom_config_lock_paths(self):
        cfg2 = {"entries": [{"id": "e.present", "kind": "present", "file": "app.yaml",
                             "pattern": "search-mode: 'AUTO'"}]}
        self.write("configs/dot-tower-ratchet.json", json.dumps(cfg2))
        code, doc = self.run_cmd("--config", "configs/dot-tower-ratchet.json",
                                 "--lock", "configs/dot-tower-ratchet.lock.json",
                                 "check", "--json")
        self.assertEqual(code, 0)
        self.assertEqual([r["id"] for r in doc["results"]], ["e.present"])
        self.assertFalse((self.root / "configs" / "dot-tower-ratchet.lock.json").exists())
        code, doc = self.run_cmd("--config", "configs/dot-tower-ratchet.json",
                                 "--lock", "configs/dot-tower-ratchet.lock.json",
                                 "update", "--dry-run")
        self.assertIn("e.present", doc["locked"])
        self.assertFalse((self.root / br.LOCK_REL).exists())

    def test_pending_as_warn_and_promotion_flag(self):
        cfg = {"flags": {"promote": False},
               "entries": [{"id": "w.date", "kind": "absent", "files": ["Enum.java"],
                            "pattern": "\\bSTANDARD\\b",
                            "pendingAs": "WARN_PENDING_DOT_LANE",
                            "promoteFlag": "promote"}]}
        self.write("configs/behavior-ratchet.json", json.dumps(cfg))
        self.write("Enum.java", "enum E { STANDARD, OFF }")
        code, s = self.states()
        self.assertEqual(code, 0)
        self.assertEqual(s["w.date"], "WARN_PENDING_DOT_LANE")
        cfg["flags"]["promote"] = True
        self.write("configs/behavior-ratchet.json", json.dumps(cfg))
        code, s = self.states()
        self.assertEqual(s["w.date"], "PENDING")

    def test_missing_config_is_error_exit1(self):
        (self.root / "configs/behavior-ratchet.json").unlink()
        code, _ = self.run_cmd("check", "--json")
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
