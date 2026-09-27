import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "demo1_tool_placement_scan.py"
BASE = "data/agent-handoff/codex-autonomy"
LOCKS = "__patch_drop__/source-edit-locks"


def run(*args, root=None):
    argv = [sys.executable, "-B", str(SCRIPT), *args]
    if root is not None:
        argv += ["--root", str(root)]
    proc = subprocess.run(argv, capture_output=True, text=True, timeout=120)
    out = proc.stdout.strip()
    return proc.returncode, json.loads(out) if out else {}


def scan(ask, *extra, root=None):
    return run("scan", ask, "--no-router", "--no-git", *extra,
               root=root)


def placements(out):
    return out.get("placements") or []


def calls(out):
    return [p["call"] for p in placements(out)]


def ids(out):
    return [p["id"] for p in placements(out)]


def make_journal(task_dir, status="in_progress", idle_hours=0):
    updated = (datetime.now(timezone.utc)
               - timedelta(hours=idle_hours)).isoformat()
    doc = {"schemaVersion": "awx.work_journal.v1", "taskId": task_dir.name,
           "agent": "devin", "status": status, "updatedAtUtc": updated,
           "purpose": "fixture", "events": []}
    task_dir.mkdir(parents=True)
    (task_dir / "journal.json").write_text(
        json.dumps(doc, ensure_ascii=True), encoding="utf-8")


def make_lease(root, topic, expired=False):
    lock_dir = root / LOCKS / f"{topic}.lock"
    lock_dir.mkdir(parents=True)
    delta = timedelta(hours=-1 if expired else 3)
    exp = (datetime.now(timezone.utc) + delta).isoformat()
    doc = {"topic": topic, "ownerId": "foreign-task",
           "expiresAtUtc": exp, "targetCount": 2}
    (lock_dir / "lease.json").write_text(
        json.dumps(doc, ensure_ascii=True), encoding="utf-8")


class ToolPlacementScanTest(unittest.TestCase):
    """지시서 실측 고통 표의 정답 타점 + 상태 기반 제안을 고정한다."""

    def test_force_restart_ranks_dev_reload_first(self):
        code, out = scan("ForceRestart meta display", "--skip-state",
                         root=ROOT)
        self.assertEqual(code, 0, out)
        self.assertEqual(out["schemaVersion"], "awx.tool-placement-scan.v1")
        self.assertEqual(ids(out)[0], "dev-reload")
        self.assertTrue(any("dev-reload" in c or "Start-RAG" in c
                            for c in calls(out)[:3]))
        self.assertNotIn("demo1-meta-display-simple-caption",
                         ids(out)[0])

    def test_rag_debug_trail_ranks_read_debug_bat_first(self):
        code, out = scan("rag debug trail launcher failed", "--skip-state",
                         root=ROOT)
        self.assertEqual(code, 0, out)
        self.assertEqual(ids(out)[0], "rag-debug-trail")
        self.assertIn("Read-RAG-Debug.bat", calls(out)[0])

    def test_selfask_ownership_points_to_contract_test(self):
        code, out = scan("SelfAskPlanner ownership orphan question",
                         "--skip-state", root=ROOT)
        self.assertEqual(code, 0, out)
        self.assertEqual(ids(out)[0], "selfask-ownership")
        self.assertTrue(any("SelfAskPlannerOwnershipContractTest" in c
                            for c in calls(out)))
        self.assertTrue(any("SelfAskPlanner.java" in c for c in calls(out)))

    def test_commit_dirty_fills_router_null_gap(self):
        code, out = scan("commit dirty files", "--skip-state", root=ROOT)
        self.assertEqual(code, 0, out)
        self.assertEqual(ids(out)[0], "conditional-git")
        self.assertTrue(any("conditional_local_git" in c
                            for c in calls(out)))
        self.assertTrue(any("agent_git_vibe_commit" in c
                            for c in calls(out)))

    def test_zombie_journal_not_safe_cleanup(self):
        code, out = scan("zombie journal cleanup", "--skip-state",
                         root=ROOT)
        self.assertEqual(code, 0, out)
        self.assertEqual(ids(out)[0], "zombie-journal")
        self.assertTrue(any("work_journal.py list --active" in c
                            for c in calls(out)[:3]))
        if "safe-cleanup" in ids(out):
            self.assertLess(ids(out).index("zombie-journal"),
                            ids(out).index("safe-cleanup"))

    def test_goal_complete_and_spend_guard(self):
        code, out = scan("goal complete next", "--skip-state", root=ROOT)
        self.assertEqual(code, 0, out)
        self.assertIn("goal-complete", ids(out))
        self.assertTrue(any("goal-complete-stop" in c for c in calls(out)))
        code, out = scan("verify all models paid", "--skip-state", root=ROOT)
        self.assertEqual(code, 0, out)
        self.assertIn("spend-guard", ids(out))
        self.assertTrue(any("spend" in c for c in calls(out)))

    def test_state_stale_journal_suggests_recovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            make_journal(root / BASE / "old-task-00000000",
                         idle_hours=30)
            make_journal(root / BASE / "fresh-task-00000000",
                         idle_hours=0)
            code, out = run("scan", "", "--no-router", root=root)
            self.assertEqual(code, 0, out)
            self.assertEqual(out["state"]["journals"]["inProgressCount"], 2)
            self.assertEqual(out["state"]["journals"]["staleCount"], 1)
            self.assertTrue(any("agent_recovery_status" in c
                                for c in calls(out)))
            # baseline entry가 항상 존재
            self.assertIn("entry-baseline", ids(out))

    def test_state_expired_lease_suggests_reclaim_dry_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            make_lease(root, "old-topic", expired=True)
            make_lease(root, "live-topic", expired=False)
            code, out = run("scan", "", "--no-router", root=root)
            self.assertEqual(code, 0, out)
            self.assertEqual(out["state"]["leases"]["expiredCount"], 1)
            self.assertEqual(out["state"]["leases"]["activeCount"], 1)
            self.assertTrue(any("reclaim --dry-run" in c
                                for c in calls(out)))

    def test_router_wrap_and_misroute_shape(self):
        # real root에서 router subprocess가 붙는지 + misroute 구조 확인
        code, out = run("scan", "forcerestart meta display", "--no-git",
                        root=ROOT)
        self.assertEqual(code, 0, out)
        router = out.get("router") or {}
        if router.get("status") == "ok":
            self.assertIn("primary", router["resolve"])
            for row in out.get("misroutes") or []:
                self.assertIn("useInstead", row)
                self.assertIn("routerPrimary", row)
        else:
            self.assertEqual(router.get("status"), "unavailable")

    def test_list_triggers(self):
        code, out = run("list-triggers")
        self.assertEqual(code, 0, out)
        trigger_ids = [t["id"] for t in out["triggers"]]
        for expected in ("dev-reload", "rag-debug-trail", "selfask-ownership",
                         "zombie-journal", "conditional-git", "goal-complete",
                         "spend-guard"):
            self.assertIn(expected, trigger_ids)

    def test_no_ask_state_skipped_still_ok(self):
        code, out = scan("", "--skip-state", root=ROOT)
        self.assertEqual(code, 0, out)
        self.assertEqual(out["matchedTriggers"], [])
        self.assertTrue(out["advisoryOnly"])

    def test_never_mutates(self):
        # scan은 읽기 전용 — 출력에 변경/실행 액션이 없고 호출 문자열만 있다
        code, out = scan("commit dirty", "--skip-state", root=ROOT)
        self.assertEqual(code, 0, out)
        self.assertTrue(all(p["existing"] for p in placements(out)))
        self.assertIsNone(out.get("applied"))
        self.assertIsNone(out.get("executed"))


if __name__ == "__main__":
    unittest.main()
