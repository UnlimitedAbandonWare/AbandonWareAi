"""Fixture tests for junit_owned_summary.py."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("junit_owned_summary.py")
SPEC = importlib.util.spec_from_file_location("junit_owned_summary", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)

XML = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="suite" tests="3" failures="1" skipped="0">
  <testcase name="kept" classname="com.example.Owned"/>
  <testcase name="broke" classname="com.example.Owned"><failure message="x"/></testcase>
  <testcase name="otherOk" classname="com.example.Other"/>
</testsuite>
"""


class JunitOwnedSummaryTest(unittest.TestCase):
    def test_named_failure_is_not_hidden_by_other_class(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TEST-suite.xml").write_text(XML, encoding="utf-8")
            payload = MOD.summarize(root, ["com.example.Owned"])
            named = payload["named"][0]
            self.assertEqual(named["status"], "FAIL")
            self.assertEqual(named["tests"], 2)
            self.assertEqual(named["failedMethods"], ["broke"])
            self.assertEqual(payload["otherClassCount"], 1)
            self.assertNotIn("otherOk", json.dumps(named))

    def test_missing_class_is_not_pass(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TEST-suite.xml").write_text(XML, encoding="utf-8")
            code = MOD.main(["--xml-dir", str(root), "--class", "com.example.Absent", "--json"])
            self.assertEqual(code, 3)

    def _req(self, xml: str, *cases: str):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TEST-suite.xml").write_text(xml, encoding="utf-8")
            argv = ["--xml-dir", str(root), "--class", "com.example.Owned"]
            for c in cases:
                argv += ["--require-case", c]
            return MOD.main(argv)

    def test_require_case_passed_exit0(self) -> None:
        clean = ('<?xml version="1.0"?><testsuite name="s" tests="1">'
                 '<testcase name="kept" classname="com.example.Owned"/>'
                 '</testsuite>')
        self.assertEqual(self._req(clean, "com.example.Owned#kept"), 0)

    def test_require_case_failed_exit4(self) -> None:
        self.assertEqual(self._req(XML, "com.example.Owned#broke"), 4)

    def test_require_case_missing_exit3(self) -> None:
        clean = ('<?xml version="1.0"?><testsuite name="s" tests="1">'
                 '<testcase name="kept" classname="com.example.Owned"/>'
                 '</testsuite>')
        self.assertEqual(self._req(clean, "com.example.Owned#notThere"), 3)

    def test_require_case_skipped_in_failed_class_exit4(self) -> None:
        xml = XML.replace('<testcase name="kept" classname="com.example.Owned"/>',
                          '<testcase name="kept" classname="com.example.Owned"><skipped/></testcase>')
        self.assertEqual(self._req(xml, "com.example.Owned#kept"), 4)

    def test_require_case_skipped_clean_class_exit3(self) -> None:
        xml = ('<?xml version="1.0"?><testsuite name="s" tests="1">'
               '<testcase name="kept" classname="com.example.Owned">'
               '<skipped/></testcase></testsuite>')
        self.assertEqual(self._req(xml, "com.example.Owned#kept"), 3)

    def test_require_case_other_class_not_counted(self) -> None:
        self.assertEqual(self._req(XML, "com.example.Other#otherOk"), 4)
        # class Owned has a failure -> 4 regardless; check payload instead
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TEST-suite.xml").write_text(XML, encoding="utf-8")
            payload = MOD.summarize(root, ["com.example.Owned"],
                                    ["com.example.Other#otherOk"])
            req = payload["requiredCases"][0]
            self.assertEqual(req["status"], "PASSED")

    def test_require_case_bad_format_exit2(self) -> None:
        self.assertEqual(self._req(XML, "noHashHere"), 2)

    def test_no_require_case_key_without_flag(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "TEST-suite.xml").write_text(XML, encoding="utf-8")
            payload = MOD.summarize(root, ["com.example.Owned"])
            self.assertNotIn("requiredCases", payload)


if __name__ == "__main__":
    unittest.main()
