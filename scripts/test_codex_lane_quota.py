"""Quota golden scenarios (DV12 10). Temp-root only."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "codex_lane_quota.py"
PLAN_BASE = "data/agent-handoff/parallel-lanes"
PLAN = "plan-quota001"


class LaneQuotaTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-quota-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        d = self.root / PLAN_BASE / PLAN
        d.mkdir(parents=True)
        (d / "plan.json").write_text(json.dumps({
            "schemaVersion": "awx.parallel-lane-plan.v1", "planId": PLAN,
            "goalKey": "goal:X",
            "lanes": [{"laneId": "A", "role": "OWNER", "quota": {}},
                      {"laneId": "INT", "role": "INTEGRATOR", "quota": {}}],
            "quotaPolicy": {"chatSmokePlanTotal": 2, "gradleConcurrent": 2}}),
            encoding="utf-8")

    def call(self, *args):
        proc = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(self.root), *args],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120)
        line = proc.stdout.strip().splitlines()
        return proc, json.loads(line[-1]) if line else {}

    def test_chat_smoke_plan_cap_refuses_third(self):
        for i in (1, 2):
            proc, row = self.call("consume", "--plan", PLAN, "--lane", "INT",
                                  "--resource", "chat-smoke",
                                  "--request-id", f"req{i}")
            self.assertEqual(proc.returncode, 0, proc.stderr[-200:])
        proc, row = self.call("consume", "--plan", PLAN, "--lane", "INT",
                              "--resource", "chat-smoke",
                              "--request-id", "req3")
        self.assertEqual(proc.returncode, 7)
        self.assertIn("quota-exhausted", row["reason"])
        proc, row = self.call("consume", "--plan", PLAN, "--lane", "INT",
                              "--resource", "chat-smoke",
                              "--request-id", "req1")
        self.assertEqual(proc.returncode, 0)
        self.assertTrue(row["dedup"])

    def test_mutex_second_acquire_refused(self):
        proc, first = self.call("acquire", "--plan", PLAN, "--lane", "INT",
                                "--resource", "server-restart",
                                "--owner", "t1")
        self.assertEqual(proc.returncode, 0)
        proc, row = self.call("acquire", "--plan", PLAN, "--lane", "INT",
                              "--resource", "server-restart",
                              "--owner", "t2")
        self.assertEqual(proc.returncode, 7)
        proc, row = self.call("release", "--plan", PLAN,
                              "--token", first["token"]["tokenId"])
        self.assertEqual(proc.returncode, 0)
        proc, row = self.call("acquire", "--plan", PLAN, "--lane", "INT",
                              "--resource", "server-restart",
                              "--owner", "t2")
        self.assertEqual(proc.returncode, 0)

    def test_gradle_cap_and_build_host_uniqueness(self):
        proc, t1 = self.call("acquire", "--plan", PLAN, "--lane", "A",
                             "--resource", "gradle",
                             "--build-host-id", "codex-lane-a")
        self.assertEqual(proc.returncode, 0)
        proc, row = self.call("acquire", "--plan", PLAN, "--lane", "B",
                              "--resource", "gradle",
                              "--build-host-id", "codex-lane-a")
        self.assertEqual(proc.returncode, 7)
        self.assertIn("buildHostId-in-use", row["reason"])
        proc, t2 = self.call("acquire", "--plan", PLAN, "--lane", "B",
                             "--resource", "gradle",
                             "--build-host-id", "codex-lane-b")
        self.assertEqual(proc.returncode, 0)
        proc, row = self.call("acquire", "--plan", PLAN, "--lane", "C",
                              "--resource", "gradle",
                              "--build-host-id", "codex-lane-c")
        self.assertEqual(proc.returncode, 7)

    def test_dead_owner_pid_shows_stale_and_reclaims(self):
        # A pid that cannot be alive on this box.
        dead_pid = 4000000
        proc, res = self.call("acquire", "--plan", PLAN, "--lane", "INT",
                              "--resource", "ollama-gpu-load",
                              "--owner", "ghost", "--pid", str(dead_pid))
        self.assertEqual(proc.returncode, 0)
        token = res["token"]["tokenId"]
        proc, row = self.call("status", "--plan", PLAN)
        self.assertEqual(proc.returncode, 0)
        states = {t["tokenId"]: t["computedState"] for t in row["tokens"]}
        self.assertEqual(states[token], "stale")
        proc, row = self.call("reclaim", "--plan", PLAN, "--token", token)
        self.assertEqual(proc.returncode, 0)
        proc, row = self.call("acquire", "--plan", PLAN, "--lane", "INT",
                              "--resource", "ollama-gpu-load",
                              "--owner", "next")
        self.assertEqual(proc.returncode, 0)

    def test_role_resource_requires_integrator(self):
        proc, row = self.call("acquire", "--plan", PLAN, "--lane", "A",
                              "--resource", "project-status-append",
                              "--owner", "t1")
        self.assertEqual(proc.returncode, 7)
        proc, row = self.call("acquire", "--plan", PLAN, "--lane", "INT",
                              "--resource", "project-status-append",
                              "--owner", "t1")
        self.assertEqual(proc.returncode, 0)

    def test_heartbeat_extends_ttl(self):
        proc, res = self.call("acquire", "--plan", PLAN, "--lane", "INT",
                              "--resource", "server-restart",
                              "--owner", "t1", "--ttl-minutes", "1")
        self.assertEqual(proc.returncode, 0)
        token = res["token"]["tokenId"]
        proc, row = self.call("heartbeat", "--plan", PLAN,
                              "--token", token, "--ttl-minutes", "60")
        self.assertEqual(proc.returncode, 0)
        proc, row = self.call("status", "--plan", PLAN)
        tok = [t for t in row["tokens"] if t["tokenId"] == token][0]
        self.assertEqual(tok["computedState"], "live")


if __name__ == "__main__":
    unittest.main()
