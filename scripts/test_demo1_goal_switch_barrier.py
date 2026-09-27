#!/usr/bin/env python3
"""Contract tests for demo1_goal_switch_barrier: owned-only switch gate,
instructional-phrase rejection, recency/lease protection, foreign immunity."""
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import demo1_goal_switch_barrier as g  # noqa: E402

BASE = "data/agent-handoff/codex-autonomy"
LOCKS = "__patch_drop__/source-edit-locks"


def _journal(root: Path, task_id: str, agent: str, status="in_progress",
             updated_at=None, purpose="task purpose"):
    stamp = updated_at or datetime.now(timezone.utc).isoformat()
    doc = {
        "schemaVersion": "awx.work_journal.v1", "taskId": task_id,
        "agent": agent, "purpose": purpose, "plannedScope": [],
        "status": status, "result": None,
        "startedAtUtc": stamp, "updatedAtUtc": stamp, "endedAtUtc": None,
        "events": [],
    }
    d = root / BASE / task_id
    d.mkdir(parents=True, exist_ok=True)
    (d / "journal.json").write_text(json.dumps(doc), encoding="utf-8")
    return doc


def _claim(root: Path, task_id: str, topic: str, agent="devin",
           owner_id="devin", released=False):
    doc = {
        "schemaVersion": "awx.agent-scope-claim.v1", "taskId": task_id,
        "agent": agent, "ownerId": owner_id, "topic": topic,
        "leaseName": f"{topic}.lock", "fingerprint": "0" * 64,
        "targets": [{"path": "scripts/x.py", "sha256": None}],
        "claimedAtUtc": datetime.now(timezone.utc).isoformat(),
        "released": released, "releasedAtUtc": None, "releaseReason": None,
    }
    d = root / BASE / task_id
    d.mkdir(parents=True, exist_ok=True)
    (d / f"scope-claim-{topic}.json").write_text(json.dumps(doc), encoding="utf-8")
    return doc


def _lease(root: Path, topic: str, owner_id: str, expires_at=None):
    doc = {
        "schemaVersion": "awx.source_edit_session.lease.v1",
        "topic": topic, "ownerId": owner_id,
        "expiresAtUtc": expires_at
        or (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "targetCount": 1, "targetPaths": ["scripts/x.py"],
    }
    d = root / LOCKS / f"{topic}.lock"
    d.mkdir(parents=True, exist_ok=True)
    (d / "lease.json").write_text(json.dumps(doc), encoding="utf-8")
    return doc


def _read_journal(root: Path, task_id: str):
    return json.loads((root / BASE / task_id / "journal.json").read_text())


def run_cli(root: Path, *argv):
    buf = io.StringIO()
    with redirect_stdout(buf):
        code = g.main([*argv])
    out = buf.getvalue().strip()
    return code, json.loads(out) if out else {}


def ago(**kw):
    return (datetime.now(timezone.utc) - timedelta(**kw)).isoformat()


class Check(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_clean_allows_open(self):
        code, out = run_cli(self.root, "check", "--root", str(self.root),
                            "--agent", "devin", "--skip-lease-scan")
        self.assertEqual(0, code, out)
        self.assertTrue(out["allowedOpen"])
        self.assertFalse(out["switchRequired"])

    def test_owned_idle_inprogress_blocks_open(self):
        _journal(self.root, "old-goal-1", "devin", updated_at=ago(hours=2))
        code, out = run_cli(self.root, "check", "--root", str(self.root),
                            "--agent", "devin", "--skip-lease-scan")
        self.assertEqual(7, code, out)
        self.assertFalse(out["allowedOpen"])
        self.assertTrue(out["switchRequired"])
        self.assertEqual("switch", out["recommendedNext"])

    def test_recent_owned_journal_is_protected_not_blocker(self):
        _journal(self.root, "fresh-goal-1", "devin", updated_at=ago(minutes=3))
        code, out = run_cli(self.root, "check", "--root", str(self.root),
                            "--agent", "devin", "--skip-lease-scan")
        self.assertEqual(0, code, out)
        self.assertTrue(out["allowedOpen"])
        self.assertTrue(out["ownedInProgress"][0]["protected"])
        self.assertEqual("recent", out["ownedInProgress"][0]["protectReason"])

    def test_foreign_inprogress_never_blocks_and_never_owned(self):
        _journal(self.root, "foreign-1", "grok", updated_at=ago(days=3))
        code, out = run_cli(self.root, "check", "--root", str(self.root),
                            "--agent", "devin", "--skip-lease-scan")
        self.assertEqual(0, code, out)
        self.assertEqual(1, out["foreignInProgressCount"])
        self.assertEqual(0, out["ownedInProgressCount"])

    def test_expired_owned_lease_counts_as_residue(self):
        _lease(self.root, "old-topic", "devin", expires_at=ago(hours=1))
        code, out = run_cli(self.root, "check", "--root", str(self.root),
                            "--agent", "devin", "--skip-lease-scan")
        self.assertEqual(7, code, out)
        self.assertEqual("expired", out["ownedLeases"][0]["status"])

    def test_foreign_lease_not_owned(self):
        _lease(self.root, "foreign-topic", "codex", expires_at=ago(hours=1))
        code, out = run_cli(self.root, "check", "--root", str(self.root),
                            "--agent", "devin", "--skip-lease-scan")
        self.assertEqual(0, code, out)
        self.assertEqual(1, out["foreignLeaseCount"])
        self.assertEqual(0, len(out["ownedLeases"]))

    def test_lease_owned_via_taskid_link(self):
        _journal(self.root, "task-abc", "devin", updated_at=ago(hours=2))
        _lease(self.root, "linked", "task-abc")
        code, out = run_cli(self.root, "check", "--root", str(self.root),
                            "--agent", "devin", "--skip-lease-scan")
        topics = [l["topic"] for l in out["ownedLeases"]]
        self.assertIn("linked", topics)

    def test_active_owned_lease_protects_its_task(self):
        _journal(self.root, "task-xyz", "devin", updated_at=ago(hours=2))
        _claim(self.root, "task-xyz", "guard-topic")
        _lease(self.root, "guard-topic", "devin")
        code, out = run_cli(self.root, "check", "--root", str(self.root),
                            "--agent", "devin", "--skip-lease-scan")
        row = out["ownedInProgress"][0]
        self.assertTrue(row["protected"], out)
        self.assertIn("active-lease", row["protectReason"])


class Switch(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _switch(self, *extra):
        return run_cli(self.root, "switch", "--root", str(self.root),
                       "--agent", "devin", "--new-purpose", "next goal",
                       "--skip-lease-scan", *extra)

    def test_switch_supersedes_owned_idle_journal(self):
        _journal(self.root, "old-goal-1", "devin", updated_at=ago(hours=2))
        code, out = self._switch()
        self.assertEqual(0, code, out)
        self.assertTrue(out["allowedOpen"])
        self.assertEqual([{"taskId": "old-goal-1", "result": "superseded"}],
                         out["closed"])
        doc = _read_journal(self.root, "old-goal-1")
        self.assertEqual("closed", doc["status"])
        self.assertEqual("superseded", doc["result"])
        self.assertTrue(doc["events"], "closing event recorded")

    def test_switch_abandoned_flag(self):
        _journal(self.root, "old-goal-2", "devin", updated_at=ago(hours=2))
        code, out = self._switch("--abandoned")
        self.assertEqual(0, code, out)
        self.assertEqual("abandoned", _read_journal(self.root, "old-goal-2")["result"])

    def test_switch_preserves_foreign_journal(self):
        _journal(self.root, "old-goal-3", "devin", updated_at=ago(hours=2))
        _journal(self.root, "foreign-1", "grok", updated_at=ago(days=3))
        code, out = self._switch()
        self.assertEqual(0, code, out)
        doc = _read_journal(self.root, "foreign-1")
        self.assertEqual("in_progress", doc["status"])
        self.assertIsNone(doc["result"])

    def test_switch_skips_recent_owned_journal(self):
        _journal(self.root, "old-goal-4", "devin", updated_at=ago(hours=2))
        _journal(self.root, "fresh-1", "devin", updated_at=ago(minutes=2))
        code, out = self._switch()
        self.assertEqual(0, code, out)
        self.assertEqual("in_progress", _read_journal(self.root, "fresh-1")["status"])
        self.assertEqual({"taskId": "fresh-1", "reason": "recent"}, out["skipped"][0])

    def test_switch_old_task_closes_only_named(self):
        _journal(self.root, "a-1", "devin", updated_at=ago(hours=2))
        _journal(self.root, "b-1", "devin", updated_at=ago(hours=3))
        code, out = self._switch("--old-task", "a-1")
        self.assertEqual(7, code, out)  # b-1 잔여 → 아직 blocked
        self.assertEqual("closed", _read_journal(self.root, "a-1")["status"])
        self.assertEqual("in_progress", _read_journal(self.root, "b-1")["status"])
        self.assertFalse(out["allowedOpen"])

    def test_switch_old_task_foreign_rejected(self):
        _journal(self.root, "foreign-1", "grok", updated_at=ago(hours=2))
        code, out = self._switch("--old-task", "foreign-1")
        self.assertEqual(6, code, out)
        self.assertEqual("in_progress", _read_journal(self.root, "foreign-1")["status"])

    def test_switch_releases_owned_claim_and_marks_file(self):
        _journal(self.root, "old-goal-5", "devin", updated_at=ago(hours=2))
        _claim(self.root, "old-goal-5", "my-topic")
        code, out = self._switch()
        self.assertEqual(0, code, out)
        claim = json.loads(
            (self.root / BASE / "old-goal-5" / "scope-claim-my-topic.json")
            .read_text())
        self.assertTrue(claim["released"])
        self.assertEqual("goal-switch", claim["releaseReason"])
        self.assertEqual("my-topic", out["claimsReleased"][0]["topic"])

    def test_switch_keep_task_exempts_current(self):
        _journal(self.root, "old-goal-6", "devin", updated_at=ago(hours=2))
        _journal(self.root, "current-1", "devin", updated_at=ago(hours=2))
        code, out = self._switch("--keep-task", "current-1")
        self.assertEqual(0, code, out)
        self.assertEqual("in_progress", _read_journal(self.root, "current-1")["status"])
        self.assertTrue(out["allowedOpen"])

    def test_switch_emits_router_resolve_for_new_purpose(self):
        _journal(self.root, "old-goal-7", "devin", updated_at=ago(hours=2))
        code, out = self._switch()
        self.assertIn("routerResolve", out)
        self.assertTrue(
            out["routerResolve"].get("reResolveRequired")
            or "router" in out["routerResolve"], out)

    def test_switch_without_purpose_requires_reresolve(self):
        _journal(self.root, "old-goal-8", "devin", updated_at=ago(hours=2))
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = g.main(["switch", "--root", str(self.root), "--agent",
                           "devin", "--skip-lease-scan"])
        out = json.loads(buf.getvalue())
        self.assertEqual(0, code, out)
        self.assertTrue(out["reResolveRequired"])


class RejectComplete(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _reject(self, text):
        return run_cli(self.root, "reject-complete", "--root", str(self.root),
                       "--text", text)

    def test_read_before_continuing_rejected(self):
        code, out = self._reject("Read AGENTS.md before continuing")
        self.assertEqual(5, code, out)
        self.assertTrue(out["rejected"])
        self.assertEqual("instructional-not-acceptance", out["reason"])
        self.assertIn("before-continuing", out["matched"])

    def test_use_dollar_skill_rejected(self):
        code, out = self._reject("Use $demo1-work-ledger for the next step")
        self.assertEqual(5, code, out)
        self.assertIn("use-skill", out["matched"])

    def test_reread_before_rejected(self):
        code, out = self._reject("re-read the spec before patching")
        self.assertEqual(5, code, out)

    def test_pure_tool_preamble_rejected(self):
        code, out = self._reject("python -B scripts/work_journal.py list --active")
        self.assertEqual(5, code, out)
        self.assertIn("tool-preamble", out["matched"])

    def test_real_acceptance_passes(self):
        code, out = self._reject(
            "acceptance: /chat hint green under Project Root")
        self.assertEqual(0, code, out)
        self.assertFalse(out["rejected"])

    def test_verified_report_passes(self):
        code, out = self._reject(
            "verified: unittest 14/14 pass, exit 0, evidence recorded")
        self.assertEqual(0, code, out)


if __name__ == "__main__":
    unittest.main()
