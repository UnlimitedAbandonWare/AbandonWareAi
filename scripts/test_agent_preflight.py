"""agent_preflight signals packet tests; temp fixtures only, no secrets."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("agent_preflight.py")
SPEC = importlib.util.spec_from_file_location("agent_preflight", SCRIPT) if SCRIPT.exists() else None
AP = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    SPEC.loader.exec_module(AP)


def write_json(path, doc):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc), encoding="utf-8")


def make_task(root, task_id, agent, events=None):
    base = root / "data" / "agent-handoff" / "codex-autonomy" / task_id
    write_json(base / "journal.json", {
        "schemaVersion": "awx.work_journal.v1", "taskId": task_id,
        "agent": agent, "purpose": "synthetic purpose", "status": "in_progress",
        "events": events or []})
    return base


def journal_task(task_id, agent):
    return {"taskId": task_id, "agent": agent, "status": "in_progress",
            "result": None, "startedAtUtc": "2026-09-26T00:00:00+00:00",
            "updatedAtUtc": "2026-09-26T00:01:00+00:00", "eventCount": 1,
            "scopeCount": 2, "purpose": "x" * 400}


class PreflightSignalsTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(AP, "agent_preflight helper is not implemented")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_release_requests_task_and_fallback_dirs(self):
        mine = make_task(self.root, "task-mine-1", "devin-desktop")
        mine.joinpath("LEASE_RELEASE_REQUEST.md").write_text(
            "# LEASE_RELEASE_REQUEST\nsecret-body-marker\n", encoding="utf-8")
        other = make_task(self.root, "task-owner-b", "codex")
        other.joinpath("LEASE_RELEASE_REQUEST.md").write_text(
            "# LEASE_RELEASE_REQUEST\n", encoding="utf-8")
        fb = self.root / "data" / "agent-handoff" / "change-plane" / "release-requests"
        fb.mkdir(parents=True)
        fb.joinpath("cf-0123456789abcdef-task-owner-b.md").write_text(
            "# LEASE_RELEASE_REQUEST\n", encoding="utf-8")
        field = AP.release_requests(self.root, agent="devin-desktop")
        self.assertEqual(field["count"], 3)
        self.assertEqual(field["addressedToAgent"], 1)
        by_path = {e["path"]: e for e in field["requests"]}
        mine_e = by_path[
            "data/agent-handoff/codex-autonomy/task-mine-1/LEASE_RELEASE_REQUEST.md"]
        self.assertFalse(mine_e["fallback"])
        self.assertEqual(mine_e["ownerAgent"], "devin-desktop")
        fb_e = by_path[
            "data/agent-handoff/change-plane/release-requests/"
            "cf-0123456789abcdef-task-owner-b.md"]
        self.assertTrue(fb_e["fallback"])
        self.assertEqual(fb_e["ownerAgent"], "codex")

    def test_release_request_bodies_never_copied(self):
        mine = make_task(self.root, "task-mine-1", "devin-desktop")
        mine.joinpath("LEASE_RELEASE_REQUEST.md").write_text(
            "# LEASE_RELEASE_REQUEST\nbody-marker-must-not-appear\n",
            encoding="utf-8")
        field = AP.release_requests(self.root)
        self.assertNotIn("body-marker-must-not-appear",
                         json.dumps(field, ensure_ascii=True))

    def test_peer_journals_summary_and_recent_signals(self):
        make_task(self.root, "task-a", "grok", events=[
            {"at": "t1", "kind": "info", "text": "ordinary note", "refs": []},
            {"at": "t2", "kind": "lease_conflict",
             "text": "conflict on x.java " + "y" * 300, "refs": []},
            {"at": "t3", "kind": "verify", "text": "AUTO: lease reclaimed",
             "refs": []}])
        make_task(self.root, "task-b", "codex")
        journals = {"status": "ok", "result": {"tasks": [
            journal_task("task-a", "grok"), journal_task("task-b", "codex"),
            journal_task("task-mine", "devin-desktop")]}}
        field = AP.peer_journals(self.root, journals, agent="devin-desktop")
        self.assertEqual(field["activeCount"], 3)
        self.assertEqual(field["ownActiveCount"], 1)
        self.assertEqual(field["peerActiveCount"], 2)
        self.assertTrue(all(len(r["purpose"]) <= 120 for r in field["journals"]))
        kinds = [s["kind"] for s in field["recentSignals"]]
        self.assertIn("lease_conflict", kinds)
        self.assertIn("verify", kinds)  # AUTO: prefix text qualifies
        self.assertNotIn("info", kinds)
        for s in field["recentSignals"]:
            self.assertLessEqual(len(s["text"]), 160)

    def test_peer_journals_skips_unreadable_and_traversal(self):
        bad = self.root / "data" / "agent-handoff" / "codex-autonomy" / "..x"
        bad.mkdir(parents=True)
        journals = {"status": "ok", "result": {"tasks": [
            journal_task("../escape", "grok"), journal_task("a b", "codex")]}}
        field = AP.peer_journals(self.root, journals)
        self.assertEqual(field["activeCount"], 2)
        self.assertEqual(field["recentSignals"], [])

    def test_signals_field_keys(self):
        field = AP.signals_field(self.root, "python", None, {})
        self.assertEqual(field["schemaVersion"], "awx.agent-preflight.signals.v1")
        for key in ("inbox", "releaseRequests", "peerJournals", "goalSwitch"):
            self.assertIn(key, field)
        self.assertEqual(field["goalSwitch"]["status"], "skipped")
        # bus script absent under the temp root -> subprocess rc!=0 -> "error"
        self.assertEqual(field["inbox"]["status"], "error")

    def test_goal_switch_requires_agent(self):
        self.assertEqual(AP.goal_switch_field(self.root, "python", None)["status"],
                         "skipped")

    def test_main_smoke_collect_missing_root_surfaces(self):
        out = AP.collect(self.root, agent=None)
        self.assertEqual(out["schemaVersion"], "awx.agent-preflight.v1")
        self.assertIn("signals", out)
        self.assertIn("releaseRequests", out["signals"])


if __name__ == "__main__":
    unittest.main()
