#!/usr/bin/env python3
"""Fixtures for the WP3 Gradle truth gate: all five verdict enums must be
reproducible from synthetic logs + JUnit XML, plus ps1 guard contract."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from scripts.test_xml_evidence import classify_verdict, summarize_xml

SUITE_PASS = """<?xml version="1.0"?>
<testsuite name="com.example.lms.FooTest" tests="3" skipped="0" failures="0" errors="0" time="0.42" timestamp="2026-09-30T13:00:00">
 <properties><property name="java.version" value="17.0.12"/></properties>
 <testcase name="a" classname="com.example.lms.FooTest" time="0.1"/>
 <testcase name="b" classname="com.example.lms.FooTest" time="0.1"/>
 <testcase name="c" classname="com.example.lms.FooTest" time="0.1"/>
</testsuite>"""

SUITE_FAIL = SUITE_PASS.replace('failures="0"', 'failures="1"').replace(
    '<testcase name="c"', '<testcase name="c"><failure message="expected"/>' +
    '</testcase><testcase name="d"')

SUITE_SKIP = SUITE_PASS.replace('skipped="0"', 'skipped="3"').replace(
    '<testcase name="a"', '<testcase name="a"><skipped/></testcase><testcase name="a2"')

EMPTY_XML = {}

COMPILE_FAIL_LOG = """
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':compileJava'.
> Compilation failed; see the compiler error output for details.
ChatRunRegistry.java:535: error: cannot find symbol
BUILD FAILED in 1m 2s
"""

CONFIG_FAIL_LOG = """
FAILURE: Build failed with an exception.
* What went wrong:
Could not compile build file 'C:\\x\\cfvm-raw\\build.gradle.kts'.
> Script compilation error
BUILD FAILED in 4s
"""

TEST_FAIL_LOG = """
> Task :test
com.example.lms.FooTest > b FAILED
    java.lang.AssertionError at FooTest.java:12
3 tests completed, 1 failed
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':test'.
> There were failing tests. See the report at: ...
BUILD FAILED in 40s
"""

GREEN_LOG = """
> Task :test
BUILD SUCCESSFUL in 12s
4 actionable tasks: 4 executed
"""

NO_TESTS_LOG = """
> Task :test NO-SOURCE
BUILD SUCCESSFUL in 2s
"""

WRAPPER_LIE_LOG = """
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':app:compileJava'.
> Compilation failed
BUILD FAILED in 9s
"""


def _xml_fixture(content: str, name="TEST-com.example.lms.FooTest.xml", stale=False):
    tmp = Path(tempfile.mkdtemp(prefix="awx-xmlev-"))
    f = tmp / name
    f.write_text(content, encoding="utf-8")
    if stale:
        import os
        os.utime(f, (0, 0))
    return tmp


class ClassifyVerdictFixtures(unittest.TestCase):
    def test_green(self):
        xml = summarize_xml(_xml_fixture(SUITE_PASS))
        self.assertEqual(xml["executed"], 3)
        out = classify_verdict(GREEN_LOG, xml, 0)
        self.assertEqual(out["verdict"], "GREEN")
        self.assertEqual(xml["jdk"], "17.0.12")

    def test_red_assertion(self):
        xml = summarize_xml(_xml_fixture(SUITE_FAIL))
        self.assertEqual(xml["failures"], 1)
        out = classify_verdict(TEST_FAIL_LOG, xml, 1)
        self.assertEqual(out["verdict"], "RED")

    def test_red_test_task_without_xml(self):
        xml = summarize_xml(_xml_fixture(""))
        out = classify_verdict(TEST_FAIL_LOG, xml, 1)
        self.assertEqual(out["verdict"], "RED")

    def test_baseline_blocked_compile(self):
        xml = summarize_xml(_xml_fixture(""))
        out = classify_verdict(COMPILE_FAIL_LOG, xml, 1)
        self.assertEqual(out["verdict"], "BASELINE_BLOCKED")

    def test_baseline_blocked_config(self):
        xml = summarize_xml(_xml_fixture(""))
        out = classify_verdict(CONFIG_FAIL_LOG, xml, 1)
        self.assertEqual(out["verdict"], "BASELINE_BLOCKED")

    def test_no_tests_no_source(self):
        xml = summarize_xml(_xml_fixture(""))
        out = classify_verdict(NO_TESTS_LOG, xml, 0)
        self.assertEqual(out["verdict"], "NO_TESTS")

    def test_root_execution_with_empty_app_uses_root_xml(self):
        tmp = _xml_fixture(SUITE_PASS)
        self.addCleanup(shutil.rmtree, tmp)
        xml = summarize_xml(tmp)
        out = classify_verdict(
            "> Task :app:test NO-SOURCE\n" + GREEN_LOG, xml, 0)
        self.assertEqual(out["verdict"], "GREEN")

    def test_root_no_source_rejects_unrelated_success_xml(self):
        tmp = _xml_fixture(SUITE_PASS)
        self.addCleanup(shutil.rmtree, tmp)
        out = classify_verdict(NO_TESTS_LOG, summarize_xml(tmp), 0)
        self.assertEqual(out["verdict"], "NO_TESTS")

    def test_app_only_no_source_rejects_unrelated_success_xml(self):
        tmp = _xml_fixture(SUITE_PASS)
        self.addCleanup(shutil.rmtree, tmp)
        out = classify_verdict(
            "> Task :app:test NO-SOURCE\nBUILD SUCCESSFUL", summarize_xml(tmp), 0)
        self.assertEqual(out["verdict"], "NO_TESTS")

    def test_root_execution_with_empty_app_still_requires_xml(self):
        tmp = _xml_fixture("")
        self.addCleanup(shutil.rmtree, tmp)
        out = classify_verdict(
            "> Task :app:test NO-SOURCE\n" + GREEN_LOG, summarize_xml(tmp), 0)
        self.assertEqual(out["verdict"], "NO_TESTS")

    def test_no_tests_all_skipped(self):
        xml = summarize_xml(_xml_fixture(SUITE_SKIP))
        self.assertTrue(xml["allSkipped"])
        out = classify_verdict(GREEN_LOG, xml, 0)
        self.assertEqual(out["verdict"], "NO_TESTS")

    def test_wrapper_exit_lie(self):
        xml = summarize_xml(_xml_fixture(""))
        out = classify_verdict(WRAPPER_LIE_LOG, xml, 0)
        self.assertEqual(out["verdict"], "WRAPPER_EXIT_LIE")

    def test_stale_xml_not_counted(self):
        tmp = _xml_fixture(SUITE_PASS, stale=True)
        xml = summarize_xml(tmp, since_epoch=4102444800)  # 2100
        self.assertEqual(xml["staleXmlCount"], 1)
        self.assertEqual(xml["executed"], 0)


class Ps1GuardContract(unittest.TestCase):
    """The ps1 must contain its guard rails; exercising the live refusal via
    powershell is the strong check but optional."""

    def setUp(self):
        self.ps1 = (ROOT / "scripts" / "gradle_truth_gate.ps1").read_text(
            encoding="utf-8")

    def test_guards_present(self):
        for needle in ("--tests", "clean task is forbidden", "--repeat requires",
                       "GRADLE_TRUTH_GATE_REFUSED", "test_xml_evidence.py"):
            self.assertIn(needle, self.ps1)

    @unittest.skipUnless(shutil.which("powershell"), "powershell unavailable")
    def test_live_refusal_clean(self):
        p = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
             str(ROOT / "scripts" / "gradle_truth_gate.ps1"),
             "-Root", ".", "-GradleArgs", "clean test --tests X"],
            capture_output=True, text=True, cwd=ROOT, timeout=60)
        self.assertEqual(p.returncode, 2)
        self.assertIn("GRADLE_TRUTH_GATE_REFUSED", p.stdout)

    @unittest.skipUnless(shutil.which("powershell"), "powershell unavailable")
    def test_live_refusal_unscoped_test(self):
        p = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
             str(ROOT / "scripts" / "gradle_truth_gate.ps1"),
             "-Root", ".", "-GradleArgs", "test"],
            capture_output=True, text=True, cwd=ROOT, timeout=60)
        self.assertEqual(p.returncode, 2)


if __name__ == "__main__":
    unittest.main()
