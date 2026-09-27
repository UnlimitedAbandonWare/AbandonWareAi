import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "codex_context_status.py"


def run(*args):
    proc = subprocess.run([sys.executable, "-B", str(SCRIPT), *args],
                          capture_output=True, text=True, timeout=60)
    out = proc.stdout.strip()
    return proc.returncode, json.loads(out) if out else {}


def _session(dirpath: Path, lines: int, size: int = 200) -> Path:
    path = dirpath / "rollout-test.jsonl"
    with path.open("w", encoding="utf-8") as fh:
        for i in range(lines):
            fh.write(json.dumps({"type": "response_item",
                                 "payload": {"type": "message",
                                             "pad": "x" * size}}) + "\n")
    return path


class CodexContextStatusTest(unittest.TestCase):
    def test_status_session_under_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = _session(Path(tmp), lines=10)
            code, out = run("status", "--session", str(session),
                            "--budget-tokens", "100000", "--detailed")
            self.assertEqual(code, 0, out)
            self.assertEqual(out["estimatedTokens"],
                             out["chars"] // 4)
            self.assertEqual(out["recommendedAction"], "ok")
            self.assertEqual(out["eventTypes"]["response_item"], 10)
            self.assertEqual(len(out["largestEventsChars"]), 10)

    def test_status_over_budget_exit4(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = _session(Path(tmp), lines=50)
            code, out = run("status", "--session", str(session),
                            "--budget-tokens", "10")
            self.assertEqual(code, 4, out)
            self.assertTrue(out["overBudget"])
            self.assertEqual(out["recommendedAction"], "stop")

    def test_status_compress_band(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = _session(Path(tmp), lines=20)
            chars = session.stat().st_size
            budget = int(chars / 4 / 0.9)  # ~90% used
            code, out = run("status", "--session", str(session),
                            "--budget-tokens", str(budget))
            self.assertEqual(code, 0)
            self.assertEqual(out["recommendedAction"], "compress")

    def test_status_input_metrics(self):
        with tempfile.TemporaryDirectory() as tmp:
            metrics = Path(tmp) / "m.json"
            metrics.write_text(json.dumps({"chars": 4000, "events": 9}))
            code, out = run("status", "--input", str(metrics),
                            "--budget-tokens", "2000")
            self.assertEqual(code, 0)
            self.assertEqual(out["estimatedTokens"], 1000)
            self.assertEqual(out["budgetUsedPct"], 50.0)

    def test_footprint_defaults_and_compare(self):
        code, before = run("footprint", "--defaults")
        self.assertEqual(code, 0, before)
        self.assertGreater(before["estimatedTokens"], 0)
        with tempfile.TemporaryDirectory() as tmp:
            b_path = Path(tmp) / "b.json"
            a_path = Path(tmp) / "a.json"
            b_path.write_text(json.dumps(before))
            after = dict(before)
            after["estimatedTokens"] = int(before["estimatedTokens"] * 0.6)
            a_path.write_text(json.dumps(after))
            code, out = run("compare", "--before", str(b_path),
                            "--after", str(a_path))
            self.assertEqual(code, 0, out)
            self.assertTrue(out["targetMet"])
            self.assertAlmostEqual(out["deltaPct"], 40.0, places=0)
            # missed target -> exit 4
            after["estimatedTokens"] = int(before["estimatedTokens"] * 0.9)
            a_path.write_text(json.dumps(after))
            code, out = run("compare", "--before", str(b_path),
                            "--after", str(a_path))
            self.assertEqual(code, 4)
            self.assertFalse(out["targetMet"])


if __name__ == "__main__":
    unittest.main()
