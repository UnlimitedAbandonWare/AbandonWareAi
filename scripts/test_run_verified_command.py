import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("runner", Path(__file__).with_name("run_verified_command.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class VerifiedCommandTest(unittest.TestCase):
    def execute(self, code, *, initial=None, timeout=10):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            if initial:
                (root / "TEST-example.Suite.xml").write_text(initial)
            child = root / "child.py"
            child.write_text(code)
            result = runner.run([sys.executable, str(child)], root, root / "evidence",
                suites=["example.Suite"], xml_dir=root, timeout=timeout)
            self.assertTrue((root / "evidence/run.json").is_file())
            return result

    def xml(self, failures=0):
        return '<testsuite name="example.Suite" tests="1" failures="'+str(failures)+'"><testcase name="one"/></testsuite>'

    def writer(self, xml):
        return 'from pathlib import Path\nPath("TEST-example.Suite.xml").write_text('+repr(xml)+')\n'

    def test_success_requires_actual_exit_and_fresh_scoped_xml(self):
        r = self.execute(self.writer(self.xml()))
        self.assertEqual((r["status"], r["exitCode"], r["totals"]["tests"]), ("passed", 0, 1))

    def test_failure_is_not_hidden_by_successful_log_write(self):
        r = self.execute(self.writer(self.xml())+'print("log copied")\nraise SystemExit(7)')
        self.assertEqual((r["status"], r["verificationExitCode"]), ("failed", 7))

    def test_missing_results_cannot_pass_even_with_zero_exit(self):
        self.assertEqual(self.execute('print("BUILD SUCCESSFUL")')["status"], "evidence_incomplete")

    def test_old_xml_is_not_reused(self):
        r = self.execute('print("UP-TO-DATE")', initial=self.xml())
        self.assertIn("stale_xml:example.Suite", r["failures"])
        self.assertEqual(r["totals"]["tests"], 0)

    def test_interruption_remains_interruption(self):
        r = self.execute('import threading\nthreading.Event().wait()', timeout=0.3)
        self.assertEqual(r["status"], "interrupted")
        self.assertNotEqual(r["verificationExitCode"], 0)

    def test_junit_failure_with_zero_command_exit_cannot_pass(self):
        r = self.execute(self.writer(self.xml(1)))
        self.assertIn("junit_failure", r["failures"])
        self.assertNotEqual(r["verificationExitCode"], 0)

    def test_mismatched_suite_is_rejected(self):
        r = self.execute(self.writer(self.xml().replace('name="example.Suite"', 'name="other.Suite"')))
        self.assertIn("invalid_xml:example.Suite", r["failures"])


if __name__ == "__main__":
    unittest.main()
