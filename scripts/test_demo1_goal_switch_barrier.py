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

    def test_goal_file_read_claim_ko_rejected(self):
        # 실측 재발 사례: goal-objective 읽기를 등록 목표 완료로 주장
        code, out = self._reject(
            "지정한 goal-objective.md를 읽었습니다. "
            "이번에 등록된 목표인 목표 파일 읽기는 완료했습니다.")
        self.assertEqual(5, code, out)
        self.assertTrue(out["rejected"])

    def test_read_goal_objective_en_rejected(self):
        code, out = self._reject("Done: I read the goal-objective.md file.")
        self.assertEqual(5, code, out)
        self.assertIn("read-goal-done", out["matched"])

    def test_reading_the_goal_rejected(self):
        code, out = self._reject("Reading the goal complete.")
        self.assertEqual(5, code, out)
        self.assertIn("read-goal-done", out["matched"])

    def test_registered_goal_read_claim_ko_rejected(self):
        code, out = self._reject("등록된 목표를 읽는 작업이 완료되었습니다.")
        self.assertEqual(5, code, out)
        self.assertIn("registered-goal-read-done", out["matched"])

    def test_goal_file_read_bare_title_rejected(self):
        code, out = self._reject("목표 파일 읽기")
        self.assertEqual(5, code, out)
        self.assertIn("read-goal-done", out["matched"])

    def test_intake_only_done_rejected(self):
        code, out = self._reject("intake-only: read goal file, done")
        self.assertEqual(5, code, out)
        self.assertIn("intake-only-done", out["matched"])

    def test_docs_only_checked_ko_rejected(self):
        code, out = self._reject("문서만 확인했습니다.")
        self.assertEqual(5, code, out)
        self.assertIn("intake-only-done", out["matched"])

    def test_real_implementation_acceptance_passes(self):
        code, out = self._reject(
            "acceptance: WP1 web.search flag default OFF; "
            "ToolRegistry matches manifest")
        self.assertEqual(0, code, out)
        self.assertFalse(out["rejected"])

    def test_implementation_with_test_evidence_passes(self):
        code, out = self._reject(
            "implemented WP1 barrier patterns; unittest 33/33 pass, exit 0")
        self.assertEqual(0, code, out)

    def test_instructional_phrase_with_concrete_evidence_accepted(self):
        # WP4: 지시 어휘가 섞인 완료 보고도 exit 0/테스트 수치 증거가 있으면 수용
        code, out = self._reject(
            "Read AGENTS.md before continuing; implemented WP2 fail-soft, "
            "gradle tests 6/6 pass, exit 0")
        self.assertEqual(0, code, out)
        self.assertFalse(out["rejected"])
        self.assertEqual("instructional-with-evidence", out["reason"])
        self.assertIn("before-continuing", out["matched"])
        self.assertIn("exit-zero", out["evidence"])
        self.assertTrue(out["evidenceAccepted"])

    def test_goal_read_claim_with_evidence_accepted(self):
        # goal 파일 언급 + 실제 구현·검증 증거가 있으면 어휘 매칭만으로 거부하지 않음
        code, out = self._reject(
            "Read the goal file, patched scripts/demo1_vibe_skill_router.py, "
            "unittest 52/52 pass exit 0")
        self.assertEqual(0, code, out)
        self.assertFalse(out["rejected"])
        self.assertIn("read-goal-done", out["matched"])
        self.assertTrue(out["evidenceAccepted"])

    def test_instructional_phrase_without_evidence_still_rejected(self):
        code, out = self._reject(
            "Read the spec before proceeding with the patch")
        self.assertEqual(5, code, out)
        self.assertTrue(out["rejected"])
        self.assertEqual("instructional-not-acceptance", out["reason"])
        self.assertEqual([], out["evidence"])

    def test_pure_tool_preamble_with_numeric_text_still_rejected(self):
        # 명령어 한 줄은 증거 유무와 무관하게 항상 reject
        code, out = self._reject(
            "python -B scripts/test_demo1_goal_switch_barrier.py")
        self.assertEqual(5, code, out)
        self.assertIn("tool-preamble", out["matched"])


class CheckGoal(unittest.TestCase):
    """check-goal — create_goal 전 안전 등록 판정(P7 방어) advisory 계약."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _goal(self, *extra):
        return run_cli(self.root, "check-goal", "--root", str(self.root),
                       *extra)

    def test_bare_check_goal_is_conservative_advisory(self):
        # 목표 상태 미확인 = 충돌 가능 — blind create_goal을 권하지 않는다
        code, out = self._goal()
        self.assertEqual(0, code, out)
        self.assertEqual("UPDATE_EXISTING", out["action"])
        self.assertEqual("unknown", out["goalState"])
        self.assertFalse(out["safeToCreate"])
        self.assertIn("get_goal", out["recoverySnippet"])
        self.assertIn("update_goal", out["recoverySnippet"])
        self.assertTrue(out["safeRegistrationProtocol"])
        self.assertIn("never retry create_goal", out["nextAction"])

    def test_active_goal_reports_update_existing(self):
        code, out = self._goal("--has-active-goal")
        self.assertEqual(0, code, out)
        self.assertEqual("UPDATE_EXISTING", out["action"])
        self.assertEqual("active", out["goalState"])
        self.assertEqual("active-goal-reported", out["decisionBasis"])

    def test_active_goal_id_implies_active(self):
        code, out = self._goal("--active-goal-id", "goal-9")
        self.assertEqual(0, code, out)
        self.assertEqual("UPDATE_EXISTING", out["action"])
        self.assertEqual("goal-9", out["activeGoalId"])

    def test_no_active_goal_allows_create_new(self):
        code, out = self._goal("--no-active-goal")
        self.assertEqual(0, code, out)
        self.assertEqual("CREATE_NEW", out["action"])
        self.assertEqual("none", out["goalState"])
        self.assertTrue(out["safeToCreate"])
        self.assertIsNone(out["nextAction"])

    def test_p7_observed_forces_update_existing(self):
        code, out = self._goal("--p7-observed")
        self.assertEqual(0, code, out)
        self.assertEqual("UPDATE_EXISTING", out["action"])
        self.assertEqual("p7-rejection-observed", out["decisionBasis"])
        self.assertTrue(out["p7Observed"])

    def test_session_scan_detects_unfinished_goal(self):
        session = self.root / "rollout-test.jsonl"
        session.write_text(
            '{"payload":{"output":"unfinished goal"}}\nunfinished goal\n',
            encoding="utf-8")
        code, out = self._goal("--session", str(session))
        self.assertEqual(0, code, out)
        self.assertEqual("UPDATE_EXISTING", out["action"])
        self.assertEqual("ok", out["sessionScan"]["status"])
        self.assertEqual(2, out["sessionScan"]["goalConflictMarkers"])
        self.assertTrue(out["p7Observed"])

    def test_session_clean_keeps_unknown_state(self):
        session = self.root / "rollout-clean.jsonl"
        session.write_text('{"payload":{"output":"ok"}}\n', encoding="utf-8")
        code, out = self._goal("--session", str(session))
        self.assertEqual(0, code, out)
        self.assertEqual("unknown", out["goalState"])
        self.assertFalse(out["p7Observed"])
        self.assertEqual(0, out["sessionScan"]["goalConflictMarkers"])

    def test_missing_session_is_io_error_not_fatal(self):
        code, out = self._goal("--session", str(self.root / "absent.jsonl"))
        self.assertEqual(0, code, out)
        self.assertEqual("io-error", out["sessionScan"]["status"])
        self.assertEqual("UPDATE_EXISTING", out["action"])

    def test_conflicting_signals_stay_conservative(self):
        code, out = self._goal("--has-active-goal", "--no-active-goal")
        self.assertEqual(0, code, out)
        self.assertEqual("UPDATE_EXISTING", out["action"])
        self.assertTrue(out["conflictingSignals"])

    def test_objective_and_thread_echoed(self):
        code, out = self._goal("--objective", "ship WP1",
                               "--thread-id", "t-123")
        self.assertEqual(0, code, out)
        self.assertEqual("ship WP1", out["objective"])
        self.assertEqual("t-123", out["threadId"])

    def test_agent_adds_local_journal_summary(self):
        _journal(self.root, "mine-1", "devin", updated_at=ago(hours=2))
        code, out = self._goal("--agent", "devin")
        self.assertEqual(0, code, out)
        self.assertEqual(["mine-1"], out["localOwnedInProgress"])

    def test_unknown_flag_is_usage_error(self):
        with self.assertRaises(SystemExit) as caught:
            g.main(["check-goal", "--root", str(self.root), "--bogus"])
        self.assertEqual(2, caught.exception.code)


if __name__ == "__main__":
    unittest.main()
