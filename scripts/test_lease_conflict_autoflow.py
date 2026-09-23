"""lease_conflict_autoflow fixture tests — every mutation inside a temp repo.

Real repo files are never touched: --root points at a fabricated checkout and
the source-edit session ps1 runs with -Root <fixture>. Powershell-dependent
cases are skipped where powershell is unavailable.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "lease_conflict_autoflow.py"
SESSION = ROOT / "__patch_drop__" / "source_edit_session.ps1"
BASE = "data/agent-handoff/codex-autonomy"
NOW = datetime.now(timezone.utc)


def sha(text):
    return hashlib.sha256(str(text).encode("utf-8")).hexdigest()


def iso(dt):
    return dt.isoformat()


def ps_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def run_tool(root, *args):
    return subprocess.run(
        [sys.executable, "-B", str(TOOL), "--root", str(root), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=90)


def last_json(proc):
    lines = [l for l in proc.stdout.splitlines() if l.strip().startswith("{")]
    return json.loads(lines[-1]) if lines else None


class Fixture:
    """Fabricated checkout: lock dir + owner journal + my journal."""

    def __init__(self, test):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-autoflow-")
        test.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "__patch_drop__" / "source-edit-locks").mkdir(parents=True)
        (self.root / "target.txt").write_text("user preimage\n")
        (self.root / "other.txt").write_text("free target\n")

    def journal(self, task_id, status="in_progress", scope=None):
        directory = self.root / BASE / task_id
        directory.mkdir(parents=True, exist_ok=True)
        doc = {"schemaVersion": "awx.work_journal.v1", "taskId": task_id,
               "agent": "fixture-agent", "purpose": "fixture",
               "plannedScope": scope or [], "status": status, "result": None,
               "startedAtUtc": iso(NOW - timedelta(hours=1)),
               "updatedAtUtc": iso(NOW), "endedAtUtc": None, "events": []}
        (directory / "journal.json").write_text(json.dumps(doc), encoding="utf-8")
        return directory

    def claim(self, task_id, topic, lease_name, owner, fingerprint):
        directory = self.root / BASE / task_id
        directory.mkdir(parents=True, exist_ok=True)
        doc = {"schemaVersion": "awx.agent-scope-claim.v1", "taskId": task_id,
               "agent": "fixture-agent", "ownerId": owner, "topic": topic,
               "leaseName": lease_name, "fingerprint": fingerprint,
               "manifestHash": "0" * 64, "manifestPath": "",
               "targets": [], "reservePaths": [], "features": [], "regions": [],
               "purpose": "fixture", "ttlMinutes": 180,
               "claimedAtUtc": iso(NOW), "released": False,
               "releasedAtUtc": None, "releaseReason": None}
        (directory / f"scope-claim-{topic}.json").write_text(
            json.dumps(doc), encoding="utf-8")

    def lease(self, topic, targets, task_id, owner="fixture-owner",
              expired=False, heartbeat=False):
        lease_id = hashlib.md5(("lease-" + topic).encode()).hexdigest()
        lock = self.root / "__patch_drop__" / "source-edit-locks" / f"{topic}.lock"
        lock.mkdir(parents=True, exist_ok=True)
        expiry = NOW - timedelta(hours=1) if expired else NOW + timedelta(hours=2)
        doc = {"schemaVersion": "awx.source_edit_session.lease.v1",
               "leaseId": lease_id, "taskIdHash": sha(task_id),
               "ownerHash": sha(owner), "ownerHostHash": sha("fixture-host"),
               "ownerProcessId": 0, "ownerProcessStartedAtUtc": "",
               "generatedAt": iso(NOW - timedelta(hours=2)),
               "startedAtUtc": iso(NOW - timedelta(hours=2)),
               "topic": topic, "role": "desktop", "ownerId": owner,
               "root": str(self.root), "expiresAtUtc": iso(expiry),
               "expiresAt": iso(expiry), "mutationAllowed": True,
               "targetCount": len(targets), "operation": "worktree-edit",
               "targetPaths": targets, "coordinationMode": "target-scoped"}
        path = lock / "lease.json"
        path.write_text(json.dumps(doc), encoding="utf-8")
        fingerprint = hashlib.sha256(path.read_bytes()).hexdigest()
        if heartbeat:
            hb_dir = self.root / "__patch_drop__" / "source-edit-heartbeats"
            hb_dir.mkdir(parents=True, exist_ok=True)
            (hb_dir / f"{lease_id}.json").write_text(json.dumps({
                "leaseId": lease_id, "leaseFingerprint": fingerprint,
                "renewedAtUtc": iso(NOW - timedelta(minutes=5)),
                "expiresAtUtc": iso(NOW + timedelta(minutes=175))}),
                encoding="utf-8")
        return path, fingerprint


class LeaseConflictAutoflowTests(unittest.TestCase):
    def setUp(self):
        self.fx = Fixture(self)
        self.owner_task = "exact-model-selection-9e8c3beb"
        self.my_task = "lease-autoflow-test-00000001"
        self.fx.journal(self.my_task)
        self.lease_path, _ = self.fx.lease(
            "exact-model-selection", ["main/java/x/llmrouteraspect.java"],
            self.owner_task)
        self.fx.journal(self.owner_task, status="in_progress")

    def test_scan_reports_overlap_owner_and_status(self):
        proc = run_tool(self.fx.root, "scan", "--targets",
                        "main/java/x/llmrouteraspect.java", "other.txt")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        row = last_json(proc)
        self.assertEqual(row["blockedTargets"], ["main/java/x/llmrouteraspect.java"])
        self.assertEqual(row["freeTargets"], ["other.txt"])
        lease = row["overlappingLeases"][0]
        self.assertEqual(lease["ownerTaskId"], self.owner_task)
        self.assertEqual(lease["status"], "active")
        self.assertIsNotNone(lease["ageSeconds"])

    def test_expired_owner_with_closed_journal_is_finishing(self):
        self.fx.lease("done-task", ["done.txt"], "done-task-00000001",
                      expired=True)
        self.fx.journal("done-task-00000001", status="closed")
        proc = run_tool(self.fx.root, "scan", "--targets", "done.txt")
        row = last_json(proc)
        lease = next(r for r in row["overlappingLeases"] if r["topic"] == "done-task")
        self.assertEqual(lease["rawStatus"], "expired")
        self.assertEqual(lease["status"], "finishing")

    def test_plan_splits_and_prompts_once(self):
        args = ("plan", "--goal-files", "main/java/x/llmrouteraspect.java",
                "other.txt", "--task", self.my_task)
        first = last_json(run_tool(self.fx.root, *args))
        self.assertEqual(first["proceed_without"], ["other.txt"])
        self.assertEqual(first["blocked"], ["main/java/x/llmrouteraspect.java"])
        self.assertEqual(first["promptState"], "emitted")
        self.assertIn(self.owner_task, first["user_prompt"])
        self.assertTrue("강제 해제는 하지 않습니다" in first["user_prompt"])
        fp = first["conflictFingerprint"]
        marker = self.fx.root / "data/agent-handoff/lease-conflict-autoflow/prompted" / (fp + ".json")
        self.assertTrue(marker.is_file())
        second = last_json(run_tool(self.fx.root, *args))
        self.assertEqual(second["promptState"], "suppressed-already-emitted")
        self.assertIsNone(second["user_prompt"])
        # preview does not mark a fresh fingerprint (다른 소유자의 다른 충돌)
        self.fx.lease("other-owner", ["other2.txt"], "other-owner-task-0001")
        self.fx.journal("other-owner-task-0001", status="in_progress")
        preview = last_json(run_tool(
            self.fx.root, "plan", "--no-mark", "--goal-files",
            "other2.txt", "--task", self.my_task))
        self.assertEqual(preview["promptState"], "emitted")
        self.assertIsNotNone(preview["user_prompt"])
        self.assertFalse((self.fx.root / "data/agent-handoff/lease-conflict-autoflow/prompted"
                          / (preview["conflictFingerprint"] + ".json")).exists())

    def test_plan_execute_delivers_request_and_journal_and_preserves_lease(self):
        before = self.lease_path.read_bytes()
        proc = run_tool(self.fx.root, "plan", "--execute",
                        "--goal-files", "main/java/x/llmrouteraspect.java",
                        "other.txt", "--task", self.my_task)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        row = last_json(proc)
        request = self.fx.root / BASE / self.owner_task / "LEASE_RELEASE_REQUEST.md"
        self.assertTrue(request.is_file())
        text = request.read_text(encoding="utf-8")
        self.assertIn("forceRelease", text)
        self.assertIn(self.owner_task, text)
        releases = [a for a in row["auto_actions"] if a["action"] == "request-release"]
        self.assertEqual(len(releases), 1)
        self.assertTrue(releases[0]["written"])
        journal = json.loads((self.fx.root / BASE / self.my_task / "journal.json").read_text())
        conflicts = [e for e in journal["events"] if e["kind"] == "lease_conflict"]
        self.assertEqual(len(conflicts), 1)
        self.assertIn("continue_partial", conflicts[0]["text"])
        # expired/foreign lease는 절대 삭제되지 않는다
        self.assertEqual(self.lease_path.read_bytes(), before)

    def test_plan_all_blocked_exit_7(self):
        proc = run_tool(self.fx.root, "plan", "--goal-files",
                        "main/java/x/llmrouteraspect.java")
        self.assertEqual(proc.returncode, 7)
        self.assertEqual(last_json(proc)["nextAction"], "await_release")

    def test_request_release_idempotent(self):
        args = ("request-release", "--task", self.owner_task,
                "--files", "main/java/x/llmrouteraspect.java")
        first = last_json(run_tool(self.fx.root, *args))
        self.assertTrue(first["written"])
        second = last_json(run_tool(self.fx.root, *args))
        self.assertTrue(second["alreadyPresent"])
        request = self.fx.root / BASE / self.owner_task / "LEASE_RELEASE_REQUEST.md"
        self.assertEqual(request.read_text(encoding="utf-8").count("LEASE_RELEASE_REQUEST"), 1)

    def test_unknown_owner_falls_back_to_release_requests_dir(self):
        self.fx.lease("orphan", ["orphan.txt"], "0" * 64)  # 해석 불가 taskIdHash
        proc = run_tool(self.fx.root, "plan", "--execute",
                        "--goal-files", "orphan.txt")
        row = last_json(proc)
        release = next(a for a in row["auto_actions"] if a["action"] == "request-release")
        self.assertTrue(release["unresolvedOwnerDir"])
        self.assertTrue((self.fx.root / release["path"]).is_file())


@unittest.skipUnless(shutil.which("powershell"), "powershell not installed")
class SessionBackedTests(unittest.TestCase):
    """Real source_edit_session.ps1 against the fixture root."""

    def setUp(self):
        self.fx = Fixture(self)
        self.my_task = "lease-autoflow-test-00000001"
        self.fx.journal(self.my_task)
        self.env = {**os.environ}
        self.env.pop("PSModulePath", None)
        self.env.pop("GIT_INDEX_FILE", None)

    def ps(self, code):
        bootstrap = ("$ErrorActionPreference='Stop'; "
                     "function Get-CimInstance { @() }; ")
        return subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
             "Bypass", "-Command", bootstrap + code],
            env=self.env, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=60)

    def test_expired_foreign_lease_keeps_nonoverlap_begin_free(self):
        self.fx.lease("foreign-expired", ["foreign.txt"], "foreign-task-x",
                      expired=True)
        manifest = self.fx.root / "mine.json"
        manifest.write_text(json.dumps({"targets": [{
            "path": "other.txt",
            "sha256": hashlib.sha256((self.fx.root / "other.txt").read_bytes()).hexdigest()}]}))
        result = self.ps(
            f"& {ps_quote(SESSION)} -Root {ps_quote(self.fx.root)} -Action begin "
            f"-Topic mine -OwnerId fixture-owner -TargetManifest {ps_quote(manifest)}; "
            "exit $LASTEXITCODE")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        # overlap target은 여전히 거부된다
        (self.fx.root / "foreign.txt").write_bytes(b"x\n")
        blocked = self.fx.root / "blocked.json"
        blocked.write_text(json.dumps({"targets": [{
            "path": "foreign.txt",
            "sha256": hashlib.sha256(b"x\n").hexdigest()}]}))
        result = self.ps(
            f"& {ps_quote(SESSION)} -Root {ps_quote(self.fx.root)} -Action begin "
            f"-Topic blocked -OwnerId fixture-owner -TargetManifest {ps_quote(blocked)}; "
            "exit $LASTEXITCODE")
        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        # 만료 lease는 복구 없이 그대로 남는다
        self.assertTrue((self.fx.root / "__patch_drop__/source-edit-locks/"
                         "foreign-expired.lock/lease.json").is_file())

    def test_heartbeat_renews_own_claim(self):
        lease_path, fingerprint = self.fx.lease(
            "my-lane", ["myfile.txt"], self.my_task + "-owner",
            owner="fixture-owner")
        self.fx.claim(self.my_task, "my-lane", "my-lane.lock",
                      "fixture-owner", fingerprint)
        proc = run_tool(self.fx.root, "heartbeat", "--task", self.my_task)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        row = last_json(proc)
        self.assertTrue(row["renewed"])
        hb_dir = self.fx.root / "__patch_drop__" / "source-edit-heartbeats"
        self.assertTrue(any(hb_dir.glob("*.json")))


if __name__ == "__main__":
    unittest.main()
