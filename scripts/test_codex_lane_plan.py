"""Lane-plan golden scenarios (DV12 9). Temp-root only."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "codex_lane_plan.py"
PLAN_BASE = "data/agent-handoff/parallel-lanes"


class LanePlanTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-plan-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def call(self, *args):
        proc = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(self.root), *args],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120)
        line = proc.stdout.strip().splitlines()
        return proc, json.loads(line[-1]) if line else {}

    def read_plan(self, plan_id):
        return json.loads((self.root / PLAN_BASE / plan_id / "plan.json")
                          .read_text(encoding="utf-8"))

    def test_disjoint_wps_get_disjoint_owner_lanes(self):
        proc, row = self.call(
            "--wp", "failover=scripts/a.py,scripts/b.py",
            "--wp", "warmup=scripts/c.py")
        self.assertEqual(proc.returncode, 0, proc.stderr[-300:])
        plan = self.read_plan(row["planId"])
        owners = [l for l in plan["lanes"] if l["role"] == "OWNER"]
        self.assertEqual(len(owners), 2)
        a = [l for l in owners if "scripts/a.py" in l["writeScope"]][0]
        b = [l for l in owners if "scripts/c.py" in l["writeScope"]][0]
        self.assertNotEqual(a["laneId"], b["laneId"])
        self.assertFalse(set(a["writeScope"]) & set(b["writeScope"]))

    def test_shared_file_merges_into_one_lane(self):
        proc, row = self.call(
            "--wp", "failover=main/java/com/example/lms/service/ChatWorkflow.java,scripts/a.py",
            "--wp", "warmup=main/java/com/example/lms/service/ChatWorkflow.java,scripts/c.py")
        self.assertIn(proc.returncode, (0, 5))
        plan = self.read_plan(row["planId"])
        owners = [l for l in plan["lanes"] if l["role"] == "OWNER"]
        self.assertEqual(len(owners), 1)
        self.assertEqual(sorted(owners[0]["wps"]), ["failover", "warmup"])
        self.assertIn("main/java/com/example/lms/service/chatworkflow.java",
                      owners[0]["writeScope"])
        self.assertIn("main/java/com/example/lms/service/chatworkflow.java",
                      plan["sharedFiles"])

    def test_single_lane_with_lanes_requested_is_serial(self):
        proc, row = self.call(
            "--wp", "one=scripts/a.py", "--wp", "two=scripts/a.py",
            "--lanes", "3")
        self.assertEqual(proc.returncode, 5)
        plan = self.read_plan(row["planId"])
        self.assertTrue(plan["serialRequired"])
        self.assertEqual(len([l for l in plan["lanes"]
                              if l["role"] == "OWNER"]), 1)

    def test_integrator_lane_contract(self):
        proc, row = self.call("--wp", "a=scripts/a.py", "--wp", "b=scripts/b.py")
        self.assertEqual(proc.returncode, 0)
        plan = self.read_plan(row["planId"])
        ints = [l for l in plan["lanes"] if l["role"] == "INTEGRATOR"]
        self.assertEqual(len(ints), 1)
        integ = ints[0]
        self.assertEqual(integ["quota"]["serverRestart"], "exclusive")
        self.assertEqual(integ["quota"]["chatSmoke"], 2)
        self.assertEqual(sorted(integ["dependsOn"]),
                         [l["laneId"] for l in plan["lanes"]
                          if l["role"] == "OWNER"])
        lane_txt = (self.root / PLAN_BASE / row["planId"] /
                    f"lane-{plan['lanes'][0]['laneId']}.txt") \
            .read_text(encoding="utf-8")
        self.assertIn(f"[LANE: {row['planId']}/", lane_txt)
        self.assertIn("write=", lane_txt)
        self.assertTrue((self.root / PLAN_BASE / row["planId"] /
                         "PLAN_KO.md").is_file())

    def test_brief_file_wp_sections(self):
        brief = self.root / "BRIEF.txt"
        brief.write_text(
            "# GOAL X\n\n## WP1 failover\n- fix main/java/x/Fail.java and "
            "src/test/java/FailTest.java\n\n## WP2 warmup\n"
            "- touch scripts/warm.py only\n\n## WP9 회귀 검증\n"
            "- run src/test/java\n", encoding="utf-8")
        proc, row = self.call("--brief", str(brief), "--goal-key",
                              "DEMO1-BRIEF-TEST")
        self.assertEqual(proc.returncode, 0, proc.stderr[-300:])
        plan = self.read_plan(row["planId"])
        roles = {l["laneId"]: l["role"] for l in plan["lanes"]}
        self.assertIn("VERIFIER", roles.values())
        self.assertEqual(plan["goalKey"], "goal:DEMO1-BRIEF-TEST")


if __name__ == "__main__":
    unittest.main()
