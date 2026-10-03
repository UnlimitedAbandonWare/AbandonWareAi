"""Rule pairs and loop-trace self-tests. No network."""
from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(__file__).with_name("jev_campaign_report_check.py")
FIX = ROOT / "data" / "agent-handoff" / "grok-jev-campaign-tooling-20260930" / "fixtures"


def _run(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, "-B", str(SCRIPT), *args],
                          capture_output=True, text=True, cwd=str(ROOT))


class ReportCheckTest(unittest.TestCase):
    def test_rule_pairs(self) -> None:
        for index in range(1, 11):
            rule = "R%d" % index
            passed = _run(["--report", str(FIX / "reports" / ("%s_pass.json" % rule))])
            failed = _run(["--report", str(FIX / "reports" / ("%s_fail.json" % rule))])
            self.assertEqual(passed.returncode, 0, passed.stdout + passed.stderr)
            self.assertEqual(failed.returncode, 1, failed.stdout + failed.stderr)
            self.assertIn(rule, failed.stdout)

    def test_trace_self_tests(self) -> None:
        traces = sorted((FIX / "loop_traces").glob("*.json"))
        self.assertGreaterEqual(len(traces), 11)
        for path in traces:
            result = _run(["--trace", str(path), "--self-test"])
            self.assertEqual(result.returncode, 0, path.name + result.stdout + result.stderr)

    def test_trace_report_mismatch(self) -> None:
        trace = FIX / "loop_traces" / "fff_stop_at_3.json"
        wrong = FIX / "reports" / "R7_pass.json"
        result = _run(["--trace", str(trace), "--report", str(wrong)])
        self.assertEqual(result.returncode, 1)

    def test_schema_error_is_exit_2(self) -> None:
        missing = FIX / "reports" / "not-a-schema.json"
        missing.write_text("{}\n", encoding="utf-8")
        try:
            result = _run(["--report", str(missing)])
        finally:
            missing.unlink(missing_ok=True)
        self.assertEqual(result.returncode, 2)

    def test_literal_probability_is_not_a_fill(self) -> None:
        raw = json.loads((FIX / "reports" / "R5_pass.json").read_text(encoding="utf-8"))
        raw["score01"] = 1.0
        raw["omittedFields"] = []
        target = FIX / "reports" / "_literal_score.json"
        target.write_text(json.dumps(raw) + "\n", encoding="utf-8")
        try:
            result = _run(["--report", str(target)])
        finally:
            target.unlink(missing_ok=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
