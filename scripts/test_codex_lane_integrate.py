"""Integration-gate golden scenarios (DV12 12). Temp-root only."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "codex_lane_integrate.py"
JBASE = "data/agent-handoff/codex-autonomy"
PLAN_BASE = "data/agent-handoff/parallel-lanes"
PLAN = "plan-int0001"


def iso(when):
    return when.isoformat()


def now():
    return datetime.now(timezone.utc)


class IntegrateGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-int-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / JBASE).mkdir(parents=True)
        (self.root / "scripts").mkdir()
        d = self.root / PLAN_BASE / PLAN
        d.mkdir(parents=True)
        (d / "plan.json").write_text(json.dumps({
            "schemaVersion": "awx.parallel-lane-plan.v1", "planId": PLAN,
            "goalKey": "goal:TEST",
            "lanes": [
                {"laneId": "A", "role": "OWNER", "wps": ["WP1"],
                 "writeScope": ["scripts/a.py"], "readScope": ["scripts/a.py"],
                 "buildHostId": "codex-lane-a", "quota": {}, "dependsOn": []},
                {"laneId": "INT", "role": "INTEGRATOR", "wps": ["i"],
                 "writeScope": ["docs/project_status.md"], "readScope": [],
                 "buildHostId": "codex-lane-int", "quota": {},
                 "dependsOn": ["A"]}],
            "quotaPolicy": {"chatSmokePlanTotal": 2}}), encoding="utf-8")

    def call(self, *args):
        proc = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(self.root), *args],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120)
        try:
            return proc, json.loads(proc.stdout)
        except json.JSONDecodeError:
            return proc, {}

    def lane_journal(self, task, lane, status="closed", tests=()):
        d = self.root / JBASE / task
        d.mkdir(parents=True, exist_ok=True)
        (d / "journal.json").write_text(json.dumps({
            "schemaVersion": "awx.work_journal.v1", "taskId": task,
            "agent": "lane-agent",
            "purpose": f"work [LANE: {PLAN}/{lane} of 2]",
            "plannedScope": ["scripts/a.py"], "status": status,
            "updatedAtUtc": iso(now()),
            "events": [{"at": iso(now()), "kind": "verify",
                        "text": t} for t in tests]}), encoding="utf-8")
        return d

    def seal(self, task, postimages):
        d = self.root / JBASE / task / "cycle-01"
        d.mkdir(parents=True, exist_ok=True)
        (d / "manifest.json").write_text(json.dumps({"version": 1, "targets": [
            {"path": p, "existed": True, "preimageSha256": "0" * 64}
            for p in postimages]}), encoding="utf-8")
        (d / "checkpoint.json").write_text(json.dumps(
            {"status": "sealed", "postimages": postimages}), encoding="utf-8")

    def test_open_owner_lane_blocks(self):
        self.lane_journal("lane-a-open-jjkk", "A", status="in_progress")
        proc, row = self.call("check", "--plan", PLAN, "--json")
        self.assertEqual(proc.returncode, 7)
        self.assertEqual(row["result"], "BLOCKED")
        self.assertTrue(any(r.startswith("lane-open") for r in row["reasons"]))

    def test_unassigned_owner_lane_blocks(self):
        proc, row = self.call("check", "--plan", PLAN, "--json")
        self.assertEqual(proc.returncode, 7)
        self.assertTrue(any(r.startswith("lane-unassigned")
                            for r in row["reasons"]))

    def test_closed_lane_matching_sha_ready_with_command(self):
        target = self.root / "scripts" / "a.py"
        target.write_text("print('a')\n", encoding="utf-8")
        sha = hashlib.sha256(target.read_bytes()).hexdigest()
        task = "lane-a-done-llmm"
        self.lane_journal(task, "A", status="closed",
                          tests=["gradlew test --tests com.example.FooTest"])
        self.seal(task, {"scripts/a.py": sha})
        proc, row = self.call("check", "--plan", PLAN, "--json")
        self.assertEqual(proc.returncode, 0, json.dumps(row)[:400])
        self.assertEqual(row["result"], "READY_FOR_INTEGRATION_TEST")
        self.assertIn("--tests com.example.FooTest",
                      row["integrationTestCommand"])

    def test_postimage_drift_blocks_and_names_writer(self):
        target = self.root / "scripts" / "a.py"
        target.write_text("changed later\n", encoding="utf-8")
        self.lane_journal("lane-a-done-nnoo", "A", status="closed")
        self.seal("lane-a-done-nnoo", {"scripts/a.py": "a" * 64})
        # A second task's checkpoint matches the live bytes -> named culprit.
        other = "foreign-writer-ppqq"
        (self.root / JBASE / other).mkdir(parents=True, exist_ok=True)
        (self.root / JBASE / other / "journal.json").write_text(json.dumps({
            "schemaVersion": "awx.work_journal.v1", "taskId": other,
            "agent": "other", "purpose": "unrelated",
            "plannedScope": [], "status": "closed", "events": [],
            "updatedAtUtc": iso(now())}), encoding="utf-8")
        self.seal(other, {"scripts/a.py": hashlib.sha256(
            target.read_bytes()).hexdigest()})
        proc, row = self.call("check", "--plan", PLAN, "--json")
        self.assertEqual(proc.returncode, 7)
        self.assertTrue(any(r.startswith("postimage-drift")
                            for r in row["reasons"]))
        drift = row["lanes"][0]["drift"]
        self.assertEqual(drift[0]["lastWriterTaskId"], other)


if __name__ == "__main__":
    unittest.main()
