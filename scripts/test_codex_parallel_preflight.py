"""Preflight golden scenarios (DV12 1-8, 11). Temp-root only; every fixture
is synthetic - no real journal/lease/claim is touched."""
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
TOOL = ROOT / "scripts" / "codex_parallel_preflight.py"
JBASE = "data/agent-handoff/codex-autonomy"
PLAN_BASE = "data/agent-handoff/parallel-lanes"
GOAL = "DEMO1-CODEX-CHAT-TIMEOUT-FAILOVER-V2-20261002"


def now():
    return datetime.now(timezone.utc)


def iso(when):
    return when.isoformat()


class PreflightScenarios(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-lanes-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / JBASE).mkdir(parents=True)
        (self.root / "scripts").mkdir()

    # ---- fixture helpers -------------------------------------------------
    def write_journal(self, task, purpose, scope, status="in_progress",
                      age_min=0, events=None, lane=None):
        d = self.root / JBASE / task
        d.mkdir(parents=True, exist_ok=True)
        doc = {"schemaVersion": "awx.work_journal.v1", "taskId": task,
               "agent": "peer-agent", "purpose": purpose,
               "plannedScope": scope, "status": status,
               "startedAtUtc": iso(now() - timedelta(minutes=age_min + 10)),
               "updatedAtUtc": iso(now() - timedelta(minutes=age_min)),
               "endedAtUtc": iso(now() - timedelta(minutes=age_min))
               if status != "in_progress" else None,
               "events": events or []}
        if lane:
            doc["purpose"] += f" [LANE: {lane[0]}/{lane[1]} of 3 role=X]"
        path = d / "journal.json"
        path.write_text(json.dumps(doc), encoding="utf-8")
        if age_min:
            old = (now() - timedelta(minutes=age_min)).timestamp()
            os.utime(path, (old, old))
        return d

    def write_checkpoint(self, task, cycle, postimages, preimages=None,
                         age_min=0):
        d = self.root / JBASE / task / cycle
        d.mkdir(parents=True, exist_ok=True)
        targets = [{"path": p, "existed": True,
                    "preimageSha256": (preimages or {}).get(p, "0" * 64)}
                   for p in postimages]
        manifest = d / "manifest.json"
        checkpoint = d / "checkpoint.json"
        manifest.write_text(json.dumps(
            {"version": 1, "targets": targets}), encoding="utf-8")
        checkpoint.write_text(json.dumps(
            {"status": "sealed", "postimages": postimages,
             "updatedAtUtc": iso(now() - timedelta(minutes=age_min))}),
            encoding="utf-8")
        if age_min:
            old = (now() - timedelta(minutes=age_min)).timestamp()
            os.utime(manifest, (old, old))
            os.utime(checkpoint, (old, old))

    def write_claim(self, task, topic, targets, released=False):
        d = self.root / JBASE / task
        d.mkdir(parents=True, exist_ok=True)
        doc = {"schemaVersion": "awx.agent-scope-claim.v1", "taskId": task,
               "agent": "other-agent", "topic": topic, "targets":
               [{"path": t, "sha256": None} for t in targets],
               "released": released,
               "claimedAtUtc": iso(now() - timedelta(minutes=5))}
        path = d / f"scope-claim-{topic}.json"
        path.write_text(json.dumps(doc), encoding="utf-8")
        return path

    def write_lease(self, topic, task, targets):
        lock = (self.root / "__patch_drop__" / "source-edit-locks"
                / f"{topic}.lock")
        lock.mkdir(parents=True)
        (lock / "lease.json").write_text(json.dumps({
            "leaseId": "0" * 32, "topic": topic,
            "taskIdHash": hashlib.sha256(str(task).encode("utf-8")).hexdigest(),
            "ownerProcessId": 0,
            "expiresAtUtc": iso(now() + timedelta(hours=1)),
            "targetPaths": targets}), encoding="utf-8")
        return lock

    def write_plan(self, plan_id="plan-test0001", lanes=None):
        d = self.root / PLAN_BASE / plan_id
        d.mkdir(parents=True, exist_ok=True)
        lanes = lanes or [
            {"laneId": "A", "role": "OWNER", "wps": ["WP1"],
             "writeScope": ["scripts/a.py"], "readScope": ["scripts/a.py"],
             "buildHostId": "codex-lane-a", "quota": {}, "dependsOn": []},
            {"laneId": "B", "role": "OWNER", "wps": ["WP2"],
             "writeScope": ["scripts/b.py"], "readScope": ["scripts/b.py"],
             "buildHostId": "codex-lane-b", "quota": {}, "dependsOn": []},
            {"laneId": "INT", "role": "INTEGRATOR", "wps": ["integration"],
             "writeScope": ["docs/project_status.md"],
             "readScope": ["scripts/a.py", "scripts/b.py"],
             "buildHostId": "codex-lane-int", "quota": {}, "dependsOn": ["A", "B"]}]
        (d / "plan.json").write_text(json.dumps(
            {"schemaVersion": "awx.parallel-lane-plan.v1", "planId": plan_id,
             "goalKey": "goal:" + GOAL, "createdAtUtc": iso(now()),
             "lanes": lanes, "quotaPolicy": {"chatSmokePlanTotal": 2,
                                           "gradleConcurrent": 2}},
            ), encoding="utf-8")
        return plan_id

    def call(self, *args):
        proc = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(self.root), *args],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120)
        try:
            return proc, json.loads(proc.stdout)
        except json.JSONDecodeError:
            return proc, {}

    # ---- scenarios -------------------------------------------------------
    def peer_journal(self, task="peer-same-goal-aaaabbbb", age_min=3,
                     status="in_progress", scope=None, lane=None, events=None):
        return self.write_journal(
            task, f"{GOAL}: same goal twin", scope or [
                "main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java",
                "src/test/java"],
            status=status, age_min=age_min, lane=lane, events=events)

    def test_s1_duplicate_live_gives_verifier(self):
        self.peer_journal(age_min=3)
        proc, row = self.call("--goal-key", GOAL,
                              "--scope", "src/test/java,main/java/com/example/lms")
        self.assertEqual(proc.returncode, 0, proc.stderr[-300:])
        self.assertEqual(row["verdict"], "DUPLICATE_GOAL_LIVE")
        self.assertEqual(row["role"], "VERIFIER")
        self.assertTrue(row["duplicates"])
        self.assertIn("summaryKo", row)

    def test_s2_quiet_peer_gives_takeover_with_baseline(self):
        d = self.peer_journal(age_min=40)
        post_sha = "f" * 64
        self.write_checkpoint("peer-same-goal-aaaabbbb", "cycle-01",
                              {"scripts/old.py": post_sha}, age_min=40)
        proc, row = self.call("--goal-key", GOAL, "--scope", "src/test/java")
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(row["verdict"], "DUPLICATE_GOAL_QUIET")
        self.assertEqual(row["role"], "TAKEOVER")
        self.assertEqual(row["baseline"]["taskId"], "peer-same-goal-aaaabbbb")
        self.assertEqual(row["baseline"]["postimages"].get("scripts/old.py"),
                         post_sha)

    def test_s3_peer_handoff_gives_takeover(self):
        d = self.peer_journal(age_min=3)
        (d / "handoff.json").write_text(json.dumps(
            {"schemaVersion": "awx.parallel-handoff.v1",
             "fromTask": "peer-same-goal-aaaabbbb"}), encoding="utf-8")
        proc, row = self.call("--goal-key", GOAL, "--scope", "src/test/java")
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(row["verdict"], "DUPLICATE_GOAL_HANDOFF")
        self.assertEqual(row["role"], "TAKEOVER")

    def test_s4_same_plan_other_lane_is_not_duplicate(self):
        plan_id = self.write_plan()
        self.peer_journal(age_min=1, scope=["scripts/b.py"],
                          lane=(plan_id, "B"))
        proc, row = self.call("--goal-key", GOAL,
                              "--lane", f"{plan_id}/A",
                              "--scope", "scripts/a.py")
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(row["verdict"], "CLEAR")
        self.assertEqual(row["role"], "OWNER")
        self.assertEqual(row["duplicates"], [])

    def test_s5_foreign_claim_overlap_gives_wait_and_free_scope(self):
        self.write_claim("other-goal-ccccdddd", "other-topic",
                         ["scripts/a.py"])
        proc, row = self.call("--goal-key", GOAL,
                              "--scope", "scripts/a.py,scripts/free.py")
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(row["verdict"], "FOREIGN_CLAIM_OVERLAP")
        self.assertEqual(row["role"], "WAIT")
        self.assertIn("scripts/free.py", row["freeScope"])
        self.assertTrue(any("scripts/a.py" in c["paths"]
                            for c in row["blockingClaims"]))

    def test_s6_unclaimed_edit_detected(self):
        target = self.root / "scripts" / "hot_edit.py"
        target.write_text("x=1\n", encoding="utf-8")
        proc, row = self.call("--goal-key", GOAL, "--scope", "scripts/hot_edit.py")
        self.assertEqual(proc.returncode, 0)
        paths = [e["path"] for e in row["unclaimedEdits"]]
        self.assertIn("scripts/hot_edit.py", paths)

    def test_s7_stale_claim_reported_dry_run(self):
        self.write_journal("old-goal-eeeeffff", "DEMO1-OLD-GOAL done",
                           ["scripts/stale.py"], status="closed", age_min=2000)
        claim_path = self.write_claim("old-goal-eeeeffff", "stale-topic",
                                      ["scripts/stale.py"])
        before = hashlib.sha256(claim_path.read_bytes()).hexdigest()
        proc, row = self.call("--goal-key", GOAL, "--scope", "scripts/other.py")
        self.assertEqual(proc.returncode, 0)
        topics = [c["topic"] for c in row["staleClaims"]["candidates"]]
        self.assertIn("stale-topic", topics)
        self.assertEqual(before,
                         hashlib.sha256(claim_path.read_bytes()).hexdigest())

    def test_s9_duplicate_live_reports_peer_task_and_occupied_files(self):
        # WP4: DUPLICATE_GOAL_LIVE 시 상대 taskId와 실제 점유 파일이 드러나야 한다.
        task = "peer-same-goal-aaaabbbb"
        self.peer_journal(task=task, age_min=3)
        self.write_claim(task, "peer-topic", ["scripts/held.py"])
        lock = (self.root / "__patch_drop__" / "source-edit-locks"
                / "peer-topic.lock")
        lock.mkdir(parents=True)
        (lock / "lease.json").write_text(json.dumps({
            "leaseId": "0" * 32, "topic": "peer-topic",
            "taskIdHash": hashlib.sha256(task.encode()).hexdigest(),
            "ownerProcessId": 0,
            "expiresAtUtc": iso(now() + timedelta(hours=1)),
            "targetPaths": ["scripts/leased.py"]}), encoding="utf-8")
        coop = self.root / "data/agent-handoff/coop-verify"
        coop.mkdir(parents=True)
        (coop / "state.json").write_text(json.dumps({
            "schemaVersion": "awx.coop-verify.v1",
            "writers": {"b1": {"editBatchId": "b1", "taskId": task,
                              "state": "EDITING",
                              "heartbeatAtUtc": iso(now()),
                              "changedPaths": ["scripts/writing.py"]}},
            "tickets": {}}), encoding="utf-8")
        proc, row = self.call(
            "--goal-key", GOAL,
            "--scope", "src/test/java,scripts/held.py,scripts/leased.py,scripts/writing.py")
        self.assertEqual(proc.returncode, 0, proc.stderr[-300:])
        self.assertEqual(row["verdict"], "DUPLICATE_GOAL_LIVE")
        dup = row["duplicates"][0]
        self.assertEqual(dup["taskId"], task)
        self.assertEqual(sorted(dup["occupiedPaths"]),
                         ["scripts/held.py", "scripts/leased.py",
                          "scripts/writing.py"])
        self.assertIn(task, row["summaryKo"])

    def test_s8_case_and_backslash_paths_match(self):
        self.write_journal("peer-case-gggghhhh",
                           f"{GOAL}: case twin",
                           ["Main\\Java\\Com\\Example\\Foo.java"], age_min=2)
        proc, row = self.call("--goal-key", GOAL,
                              "--scope", "main/java/com/example/foo.java")
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(row["verdict"], "DUPLICATE_GOAL_LIVE")

    def test_s11_verifier_lane_write_is_violation(self):
        lanes = [{"laneId": "A", "role": "OWNER", "wps": ["WP1"],
                  "writeScope": ["scripts/a.py"], "readScope": ["scripts/a.py"],
                  "buildHostId": "codex-lane-a", "quota": {}, "dependsOn": []},
                 {"laneId": "V", "role": "VERIFIER", "wps": ["WP9"],
                  "writeScope": [], "readScope": ["scripts/a.py"],
                  "buildHostId": "codex-lane-v", "quota": {}, "dependsOn": []},
                 {"laneId": "INT", "role": "INTEGRATOR", "wps": ["i"],
                  "writeScope": ["docs/project_status.md"], "readScope": [],
                  "buildHostId": "codex-lane-int", "quota": {}, "dependsOn": ["A"]}]
        plan_id = self.write_plan("plan-viol0001", lanes)
        task = "lane-v-worker-iiiijjjj"
        self.write_journal(task, f"{GOAL}: verifier lane work",
                           ["scripts/a.py"], lane=(plan_id, "V"))
        # VERIFIER wrote a file outside data/agent-handoff -> violation.
        self.write_checkpoint(task, "cycle-01",
                              {"scripts/violated.py": "e" * 64})
        proc, row = self.call("--lane", f"{plan_id}/V", "--strict")
        self.assertEqual(proc.returncode, 7)
        self.assertEqual(row["verdict"], "LANE_VIOLATION")
        self.assertTrue(row["laneViolation"])



    def test_own_live_lease_is_excluded_from_overlapping_claims(self):
        # Caller taskIdHash == sha256(task) must not be reported as overlap.
        task = "self-lease-aaaabbbb"
        topic = "self-lease-topic"
        self.write_lease(topic, task, ["scripts/mine.py"])
        proc, row = self.call("--goal-key", GOAL, "--task", task,
                              "--scope", "scripts/mine.py")
        self.assertEqual(proc.returncode, 0, proc.stderr[-400:])
        self.assertNotIn(topic, [c.get("lease") for c in row["overlappingClaims"]])
        self.assertNotIn(topic, [c.get("lease") for c in row["blockingClaims"]])
        self.assertEqual(row["verdict"], "CLEAR")

    def test_foreign_live_lease_remains_foreign_claim_overlap(self):
        task = "self-lease-aaaabbbb"
        topic = "foreign-lease-topic"
        self.write_lease(topic, "other-lease-ccccdddd", ["scripts/theirs.py"])
        proc, row = self.call("--goal-key", GOAL, "--task", task,
                              "--scope", "scripts/theirs.py")
        self.assertEqual(proc.returncode, 0, proc.stderr[-400:])
        self.assertEqual(row["verdict"], "FOREIGN_CLAIM_OVERLAP")
        self.assertIn(topic, [c.get("lease") for c in row["overlappingClaims"]])
        self.assertIn(topic, [c.get("lease") for c in row["blockingClaims"]])

if __name__ == "__main__":
    unittest.main()
