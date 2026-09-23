import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "scripts" / "analyze_build_output.py"
SPEC = importlib.util.spec_from_file_location("analyze_build_output", SCRIPT_PATH)
analyze_build_output = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(analyze_build_output)


class AnalyzeBuildOutputTest(unittest.TestCase):

    def scan(self, text: str):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "gradle.log"
            log.write_text(text, encoding="utf-8")
            return analyze_build_output.scan_log(log)

    def test_summary_only_build_failure_is_not_zero_risk(self):
        hits = self.scan(
            "> Task :compileJava FAILED\n\n"
            "BUILD FAILED in 7s\n"
            "1 actionable task: 1 executed\n"
        )
        _, overall = analyze_build_output.aggregate(hits, {})

        self.assertEqual(1, hits["gradle_build_failed"])
        self.assertGreater(overall, 0.0)

    def test_completed_with_failures_banner_is_not_zero_risk(self):
        logs = [
            "FAILURE: Build completed with 2 failures.\n",
            "\x1b[31mFAILURE: Build completed with 1 failure.\x1b[0m\n",
            "FAILURE:\x1b[31m Build completed with 2 failures.\x1b[0m\n",
            "FAILURE: Build completed with \x1b[31m2 failures.\x1b[0m\n",
        ]

        for text in logs:
            with self.subTest(text=text):
                hits = self.scan(text)
                _, overall = analyze_build_output.aggregate(hits, {})
                self.assertEqual(1, hits["gradle_build_failed"])
                self.assertGreater(overall, 0.0)

    def test_task_status_only_failure_is_not_zero_risk(self):
        hits = self.scan("> Task :compileJava FAILED\n")
        _, overall = analyze_build_output.aggregate(hits, {})

        self.assertEqual(0, hits["gradle_build_failed"])
        self.assertEqual(1, hits["gradle_task_failed"])
        self.assertGreater(overall, 0.0)

    def test_task_status_and_diagnostic_are_one_logical_failure(self):
        hits = self.scan(
            "> Task :compileJava FAILED\n\n"
            "Execution failed for task ':compileJava'.\n"
        )

        self.assertEqual(1, hits["gradle_task_failed"])

    def test_ansi_colored_task_status_is_recognized(self):
        logs = [
            "> Task :compileJava \x1b[31mFAILED\x1b[0m\n",
            "\x1b[1m> Task :compileJava \x1b[31mFAILED\x1b[0m\n",
        ]

        for text in logs:
            with self.subTest(text=text):
                hits = self.scan(text)
                self.assertEqual(0, hits["gradle_build_failed"])
                self.assertEqual(1, hits["gradle_task_failed"])

    def test_banner_and_summary_are_one_logical_build_failure(self):
        hits = self.scan(
            "FAILURE: Build failed with an exception.\n\n"
            "* What went wrong:\n"
            "Execution failed for task ':compileJava'.\n\n"
            "BUILD FAILED in 7s\n"
        )

        self.assertEqual(1, hits["gradle_build_failed"])

    def test_ansi_colored_failure_markers_are_recognized(self):
        logs = [
            "\x1b[31mFAILURE: Build failed with an exception.\x1b[0m\n",
            "\x1b[1;31mBUILD FAILED in 7s\x1b[0m\n",
            "\x1b[31m  BUILD FAILED in 7s\x1b[0m\n",
        ]

        for text in logs:
            with self.subTest(text=text):
                hits = self.scan(text)
                self.assertEqual(1, hits["gradle_build_failed"])


if __name__ == "__main__":
    unittest.main()
