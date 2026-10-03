"""f01b_focused_verify unittest — 수집/파싱/판정 (Gradle 미실행).

Contract DEMO1-DEVIN-F01B-POST-TOOLS-20260929 항목 3.
"""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("f01b_focused_verify.py")
SPEC = importlib.util.spec_from_file_location("f01b_focused_verify", SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("f01b_focused_verify", MOD)
    SPEC.loader.exec_module(MOD)

XML_OK = """<?xml version="1.0"?>
<testsuite name="com.example.lms.jobs.JdbcJobServiceTest" tests="7"
           failures="0" errors="0" skipped="0"/>
"""
XML_FAIL = """<?xml version="1.0"?>
<testsuite name="com.example.lms.jobs.OtherTest" tests="4"
           failures="2" errors="0" skipped="0"/>
"""


def make_root(tmp: Path) -> Path:
    suites = tmp / "handoff"
    suites.mkdir()
    (suites / "x-green-suites.json").write_text(json.dumps({
        "suites": [{"file": "TEST-com.example.lms.jobs.A.xml", "tests": 5},
                   {"suite": "com.example.lms.api.B", "tests": 3}]}),
        encoding="utf-8")
    results = tmp / "build" / "test-results" / "test"
    results.mkdir(parents=True)
    return tmp


class FocusedVerifyTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = make_root(Path(self.tmp.name))

    def run_main(self, *argv):
        out = self.root / "out" / "fv.json"
        code = MOD.main(["--root", str(self.root), "--json-out", str(out),
                         *argv])
        payload = json.loads(out.read_text(encoding="utf-8")) \
            if out.is_file() else None
        return code, payload

    def test_collect_only_default_exit_3(self):
        code, payload = self.run_main("--suites-dir", "handoff")
        self.assertEqual(3, code)
        self.assertEqual("skipped", payload["run"])
        self.assertIn("com.example.lms.jobs.A", payload["patterns"])
        self.assertIn("com.example.lms.api.B", payload["patterns"])
        frag = payload["verifyFragment"]
        self.assertEqual("skipped", frag["run"])
        self.assertFalse(frag["ok"])

    def test_collect_only_beats_execute(self):
        code, payload = self.run_main("--suites-dir", "handoff",
                                      "--execute", "--collect-only")
        self.assertEqual(3, code)
        self.assertEqual("skipped", payload["run"])

    def test_parse_only_passed(self):
        res = self.root / "build/test-results/test"
        (res / "TEST-com.example.lms.jobs.A.xml").write_text(
            XML_OK, encoding="utf-8")
        code, payload = self.run_main("--suites-dir", "handoff",
                                      "--parse-only")
        self.assertEqual(0, code)
        self.assertEqual("passed", payload["verdict"])
        frag = payload["verifyFragment"]
        self.assertEqual("executed", frag["run"])
        self.assertEqual(7, frag["totals"]["tests"])
        self.assertTrue(frag["ok"])

    def test_parse_only_failures_exit_2(self):
        res = self.root / "build/test-results/test"
        (res / "TEST-a.xml").write_text(XML_OK, encoding="utf-8")
        (res / "TEST-b.xml").write_text(XML_FAIL, encoding="utf-8")
        code, payload = self.run_main("--suites-dir", "handoff",
                                      "--parse-only")
        self.assertEqual(2, code)
        self.assertEqual("failed", payload["verdict"])
        self.assertEqual(2, payload["verifyFragment"]["totals"]["failures"])

    def test_no_results_is_failure_not_pass(self):
        code, payload = self.run_main("--suites-dir", "handoff",
                                      "--parse-only")
        self.assertEqual(2, code)
        self.assertEqual("no-results", payload["verdict"])

    def test_plan_filters_merged(self):
        (self.root / "plan.json").write_text(json.dumps(
            {"filters": ["*Understanding*"]}), encoding="utf-8")
        code, payload = self.run_main("--suites-dir", "handoff",
                                      "--plan", "plan.json")
        self.assertEqual(3, code)
        self.assertIn("*Understanding*", payload["patterns"])
        self.assertEqual(3, payload["patternCount"])


if __name__ == "__main__":
    unittest.main()
