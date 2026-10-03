"""Objective score, rubric, and drift. No network and no exec."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(__file__).with_name("jev_campaign_score.py")
REQUIRED = (ROOT / "data" / "agent-handoff" / "grok-jev-campaign-tooling-20260930"
            / "fixtures" / "required_checks.json")


def _run(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, "-B", str(SCRIPT), *args],
                          capture_output=True, text=True, cwd=str(ROOT))


class ScoreTest(unittest.TestCase):
    def _results(self, passed_ids: set[str] | None, missing: str | None = None) -> Path:
        doc = json.loads(REQUIRED.read_text(encoding="utf-8"))
        rows = []
        for item in doc["checks"]:
            if item == missing:
                continue
            rows.append({"id": item, "executed": True, "passed": item in (passed_ids or set(doc["checks"]))})
        path = Path(tempfile.mkdtemp()) / "results.json"
        path.write_text(json.dumps({"results": rows}), encoding="utf-8")
        return path

    def test_all_passed(self) -> None:
        path = self._results(None)
        result = _run(["--results", str(path), "--required", str(REQUIRED)])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        body = json.loads(result.stdout)
        self.assertEqual(body["testResult"], "PASS")
        self.assertEqual(body["objectiveScore"], 100)
        self.assertEqual(body["coverage"], 1)

    def test_failed_required_exits_1(self) -> None:
        doc = json.loads(REQUIRED.read_text(encoding="utf-8"))
        path = self._results(set(doc["checks"][1:]))
        result = _run(["--results", str(path), "--required", str(REQUIRED)])
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        body = json.loads(result.stdout)
        self.assertEqual(body["testResult"], "FAIL")
        self.assertIsNotNone(body["objectiveScore"])
        self.assertLess(body["objectiveScore"], 100)

    def test_missing_is_inconclusive(self) -> None:
        doc = json.loads(REQUIRED.read_text(encoding="utf-8"))
        path = self._results(set(doc["checks"]), missing=doc["checks"][0])
        loose = _run(["--results", str(path), "--required", str(REQUIRED)])
        strict = _run(["--results", str(path), "--required", str(REQUIRED), "--strict"])
        self.assertEqual(loose.returncode, 0, loose.stdout + loose.stderr)
        self.assertEqual(json.loads(loose.stdout)["testResult"], "INCONCLUSIVE")
        self.assertIsNone(json.loads(loose.stdout)["objectiveScore"])
        self.assertEqual(strict.returncode, 4, strict.stdout + strict.stderr)

    def test_drift_exits_2(self) -> None:
        doc = json.loads(REQUIRED.read_text(encoding="utf-8"))
        doc["expectedSha256"] = "0" * 64
        path = Path(tempfile.mkdtemp()) / "required.json"
        path.write_text(json.dumps(doc), encoding="utf-8")
        results = self._results(None)
        result = _run(["--results", str(results), "--required", str(path)])
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)

    def test_rubric_does_not_average_or_exec(self) -> None:
        path = Path(tempfile.mkdtemp()) / "assess.json"
        path.write_text(json.dumps({
            "evidenceFit": "L4",
            "regressionRisk": "LOW",
        }), encoding="utf-8")
        result = _run(["--assess", str(path)])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        body = json.loads(result.stdout)
        self.assertEqual(body["modelAssessment"]["evidenceFit"], "1.0")
        self.assertEqual(body["modelAssessment"]["regressionRisk"], "LOW")
        self.assertFalse(body["averaged"])
        path.write_text(json.dumps({"evidenceFit": "os.system('dir')", "regressionRisk": "UNKNOWN"}),
                        encoding="utf-8")
        result = _run(["--assess", str(path)])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIsNone(json.loads(result.stdout)["modelAssessment"]["evidenceFit"])
        path.write_text(json.dumps({"evidenceFit": "L0", "regressionRisk": "LOW", "extra": "L1"}),
                        encoding="utf-8")
        result = _run(["--assess", str(path)])
        self.assertEqual(result.returncode, 2)

    def test_bench_reports_p95(self) -> None:
        result = _run(["--bench"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        body = json.loads(result.stdout)
        self.assertEqual(body["bench"]["samples"], 10000)
        self.assertIn("p95Ms", body["bench"])


if __name__ == "__main__":
    unittest.main()
