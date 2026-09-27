"""Change-plane tests; every mutation happens inside a temporary root."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "agent_change_plane.py"
LOCKS = Path("__patch_drop__") / "source-edit-locks"
STORE = Path("data") / "agent-handoff" / "change-plane"


class AgentChangePlaneTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-plane-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "__patch_drop__").mkdir()
        (self.root / "target.txt").write_text("user preimage\n")
        (self.root / "other.txt").write_text("other file\n")
        (self.root / "third.txt").write_text("third file\n")
        # Stub the process inventory like the lease tests do: an unrelated
        # live git.exe is otherwise a writer hazard for every root.
        stub = self.root / "session_stub.ps1"
        stub.write_text(
            "function Get-CimInstance { @() }\n"
            f"& '{ROOT / '__patch_drop__' / 'source_edit_session.ps1'}' @args\n"
            "exit $LASTEXITCODE\n",
            encoding="utf-8")
        self.env = {**os.environ, "AWX_SCOPE_SESSION_PS1": str(stub)}
        self.env.pop("PSModulePath", None)

    def call(self, *args, expect=None):
        proc = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(self.root), *args],
            env=self.env, capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=120)
        if expect is not None:
            self.assertEqual(proc.returncode, expect,
                             f"{args}: rc={proc.returncode} out={proc.stdout} err={proc.stderr[-400:]}")
        return proc, json.loads(proc.stdout.strip().splitlines()[-1])

    def propose(self, agent="devin-a", task="task-a", path="target.txt", **kw):
        args = ["propose", "--agent", agent, "--task", task, "--path", path]
        for key, value in kw.items():
            flag = "--" + key.replace("_", "-")
            if isinstance(value, list):
                for item in value:
                    args += [flag, str(item)]
            else:
                args += [flag, str(value)]
        _, row = self.call(*args, expect=0)
        return row["intent"]

    def admit(self, intent_id, expect=0):
        return self.call("admit", "--intent", intent_id, expect=expect)[1]

    def lock_dirs(self):
        locks = self.root / LOCKS
        return {p.name for p in locks.glob("*.lock")} if locks.is_dir() else set()

    def events(self):
        return self.call("events", "--tail", "500", expect=0)[1]["events"]

    def intents_state(self):
        return json.loads(
            (self.root / STORE / "intents.json").read_text(encoding="utf-8"))

    def intent_row(self, intent_id):
        return [i for i in self.intents_state()["intents"]
                if i["intentId"] == intent_id][0]

    def journal(self, task, status="closed"):
        d = self.root / "data" / "agent-handoff" / "codex-autonomy" / task
        d.mkdir(parents=True, exist_ok=True)
        (d / "journal.json").write_text(json.dumps(
            {"schemaVersion": "awx.work_journal.v1", "taskId": task,
             "agent": "devin-a", "status": status, "events": []}),
            encoding="utf-8")

    def test_propose_admit_renew_seal_end_cycle(self):
        intent = self.propose()
        self.assertEqual(intent["state"], "proposed")
        self.assertIsNone(intent["fence"])
        admitted = self.admit(intent["intentId"])
        self.assertTrue(admitted["admitted"])
        self.assertEqual(admitted["fence"], "f-1")
        self.assertIn(intent["intentId"] + ".lock", self.lock_dirs())

        proc, _ = self.call("renew", "--intent", intent["intentId"],
                            "--fence", "f-999")
        self.assertEqual(proc.returncode, 3)
        _, renewed = self.call("renew", "--intent", intent["intentId"],
                               "--fence", "f-1", expect=0)
        self.assertTrue(renewed["renewed"])

        _, sealed = self.call("seal", "--intent", intent["intentId"],
                              "--fence", "f-1", expect=0)
        self.assertTrue(sealed["sealed"])
        _, released = self.call("end", "--intent", intent["intentId"],
                                "--fence", "f-1", expect=0)
        self.assertTrue(released["released"])
        self.assertNotIn(intent["intentId"] + ".lock", self.lock_dirs())
        kinds = [e["type"] for e in self.events()]
        self.assertEqual(kinds[0], "intent.proposed")
        self.assertIn("intent.admitted", kinds)
        self.assertIn("intent.renewed", kinds)
        self.assertIn("intent.sealed", kinds)
        self.assertIn("intent.released", kinds)

    def test_second_admit_same_path_blocked_without_begin(self):
        first = self.admit(self.propose(task="task-a")["intentId"])
        self.assertTrue(first["admitted"])
        second_intent = self.propose(agent="codex-b", task="task-b")
        proc, second = self.call("admit", "--intent", second_intent["intentId"])
        self.assertEqual(proc.returncode, 7)
        self.assertFalse(second["admitted"])
        # begin was never invoked for the blocked intent: no second lock dir
        self.assertNotIn(second_intent["intentId"] + ".lock", self.lock_dirs())
        self.assertEqual(len([d for d in self.lock_dirs()
                              if d.endswith(".lock")]), 1)
        types = [e["type"] for e in self.events()
                 if e.get("intentId") == second_intent["intentId"]]
        self.assertNotIn("intent.admitted", types)
        # one release_request event for this conflict fingerprint
        reqs = [e for e in self.events() if e["type"] == "release_request"]
        self.assertEqual(len(reqs), 1)
        self.assertEqual(reqs[0]["detail"]["fingerprint"],
                         second["conflictFingerprint"])

    def test_disjoint_paths_both_admit(self):
        first = self.admit(self.propose(task="task-a")["intentId"])
        second = self.admit(self.propose(agent="grok-b", task="task-b",
                                         path="other.txt")["intentId"])
        self.assertTrue(first["admitted"])
        self.assertTrue(second["admitted"])
        self.assertNotEqual(first["fence"], second["fence"])
        self.call("end", "--intent", first["intentId"], "--fence",
                  first["fence"], expect=0)
        self.call("end", "--intent", second["intentId"], "--fence",
                  second["fence"], expect=0)

    def test_expired_lease_still_blocks_and_never_unlocks(self):
        intent = self.propose(task="task-a")
        admitted = self.admit(intent["intentId"])
        self.assertTrue(admitted["admitted"])
        lease_name = admitted["lease"]["leaseName"]
        lease_path = self.root / LOCKS / lease_name / "lease.json"
        lease = json.loads(lease_path.read_text(encoding="utf-8"))
        past = (datetime.now(timezone.utc) - timedelta(minutes=5)).isoformat()
        lease["expiresAtUtc"] = past
        lease["expiresAt"] = past
        lease_path.write_text(json.dumps(lease), encoding="utf-8")

        _, status = self.call("status", expect=0)
        mine = [i for i in status["intents"]["items"]
                if i["intentId"] == intent["intentId"]]
        self.assertEqual(mine[0]["effectiveState"], "expired_lease")
        self.assertTrue((self.root / STORE / "LATEST.md").is_file())

        blocked_intent = self.propose(agent="cline-b", task="task-b")
        proc, blocked = self.call("admit", "--intent", blocked_intent["intentId"])
        self.assertEqual(proc.returncode, 7)
        self.assertFalse(blocked["admitted"])
        # expired lease is still on disk - the plane never force-unlocks
        self.assertTrue(lease_path.is_file())
        self.assertIn(lease_name, self.lock_dirs())

    def test_plan_emits_release_request_once_per_fingerprint(self):
        self.admit(self.propose(task="task-a")["intentId"])
        blocked = self.propose(agent="codex-b", task="task-b")
        proc, plan1 = self.call("plan")
        self.assertEqual(proc.returncode, 7)
        self.assertEqual(len(plan1["blocked"]), 1)
        fp = plan1["blocked"][0]["conflictFingerprint"]
        reqs = [e for e in self.events() if e["type"] == "release_request"]
        self.assertEqual(len(reqs), 1)
        proc, plan2 = self.call("plan")
        reqs = [e for e in self.events() if e["type"] == "release_request"]
        self.assertEqual(len(reqs), 1)
        self.assertEqual(reqs[0]["detail"]["fingerprint"], fp)

    def test_request_release_writes_owner_doc(self):
        owner_task = "owner-task-1"
        opened = subprocess.run(
            [sys.executable, "-B", str(ROOT / "scripts" / "work_journal.py"),
             "open", "--root", str(self.root), "--task-id", owner_task,
             "--agent", "codex-b", "--purpose", "owner task",
             "--scope", "target.txt"],
            capture_output=True, text=True, timeout=30)
        self.assertEqual(opened.returncode, 0, opened.stdout + opened.stderr)
        self.admit(self.propose(task=owner_task)["intentId"])
        mine = self.propose(agent="devin-a", task="task-me")
        proc, out = self.call("request-release", "--intent", mine["intentId"],
                              expect=0)
        self.assertTrue(out["emitted"])
        doc = self.root / "data/agent-handoff/codex-autonomy" / owner_task / "LEASE_RELEASE_REQUEST.md"
        self.assertTrue(doc.is_file())
        proc, again = self.call("request-release", "--intent",
                                mine["intentId"], expect=0)
        self.assertFalse(again["emitted"])
        self.assertEqual(again.get("suppressed"), "already-requested")

    def test_surface_conflict_blocks_without_lease_write(self):
        first = self.propose(task="task-a", surface=["runtime"])
        self.admit(first["intentId"])
        second = self.propose(agent="codex-b", task="task-b",
                              path="other.txt", surface=["runtime"])
        proc, out = self.call("admit", "--intent", second["intentId"])
        self.assertEqual(proc.returncode, 7)
        self.assertEqual(out["reason"], "surface-conflict")
        self.assertNotIn(second["intentId"] + ".lock", self.lock_dirs())
        _, status = self.call("status", expect=0)
        self.assertEqual(status["owners"]["runtime"]["intentId"],
                         first["intentId"])
        self.assertIsNone(status["owners"]["build"])

    def test_preflight_field_projection(self):
        intent = self.propose(task="task-a", surface=["build"])
        self.admit(intent["intentId"])
        _, row = self.call("preflight", expect=0)
        plane = row["changePlane"]
        self.assertTrue(plane["present"])
        self.assertEqual(plane["activeIntents"], 1)
        self.assertEqual(plane["blockedCount"], 0)
        self.assertEqual(plane["buildOwner"]["intentId"], intent["intentId"])
        self.assertIsNone(plane["runtimeOwner"])
        self.assertGreaterEqual(plane["lastEventSeq"], 2)

    def test_reconcile_supersedes_ghost_after_owner_close(self):
        owner = self.admit(self.propose(task="task-a")["intentId"])
        ghost = self.propose(agent="codex-b", task="task-b")
        proc, _ = self.call("admit", "--intent", ghost["intentId"])
        self.assertEqual(proc.returncode, 7)
        self.call("end", "--intent", owner["intentId"], "--fence",
                  owner["fence"], expect=0)
        _, dry = self.call("reconcile", "--dry-run", expect=0)
        self.assertEqual(dry["blockedBefore"], 1)
        self.assertIn(ghost["intentId"],
                      [e["intentId"] for e in dry["superseded"]])
        # dry-run mutates nothing
        self.assertEqual(self.intent_row(ghost["intentId"])["state"],
                         "blocked")
        _, run = self.call("reconcile", expect=0)
        self.assertEqual(run["blockedAfter"], 0)
        self.assertIn(ghost["intentId"],
                      [e["intentId"] for e in run["superseded"]])
        self.assertEqual(self.intent_row(ghost["intentId"])["state"],
                         "superseded")
        kinds = [e["type"] for e in self.events()
                 if e.get("intentId") == ghost["intentId"]]
        self.assertIn("intent.superseded", kinds)
        receipts = list((self.root / STORE).glob("reconcile-receipt-*.json"))
        self.assertTrue(receipts)

    def test_reconcile_keeps_blocked_when_live_lock_remains(self):
        owner = self.admit(self.propose(task="task-a")["intentId"])
        ghost = self.propose(agent="codex-b", task="task-b")
        proc, _ = self.call("admit", "--intent", ghost["intentId"])
        self.assertEqual(proc.returncode, 7)
        _, dry = self.call("reconcile", "--dry-run", expect=0)
        self.assertIn(ghost["intentId"],
                      [e["intentId"] for e in dry["stillBlocked"]])
        _, run = self.call("reconcile", expect=0)
        self.assertEqual(run["blockedAfter"], 1)
        self.assertEqual(self.intent_row(ghost["intentId"])["state"],
                         "blocked")
        # the live lease is never touched
        self.assertIn(owner["lease"]["leaseName"], self.lock_dirs())
        kinds = [e["type"] for e in self.events()
                 if e.get("intentId") == ghost["intentId"]]
        self.assertNotIn("intent.superseded", kinds)

    def test_reconcile_revives_intent_with_open_owner_journal(self):
        owner = self.admit(self.propose(task="task-a")["intentId"])
        self.journal("task-b", status="in_progress")
        ghost = self.propose(agent="codex-b", task="task-b")
        proc, _ = self.call("admit", "--intent", ghost["intentId"])
        self.assertEqual(proc.returncode, 7)
        self.call("end", "--intent", owner["intentId"], "--fence",
                  owner["fence"], expect=0)
        _, run = self.call("reconcile", expect=0)
        self.assertIn(ghost["intentId"],
                      [e["intentId"] for e in run["revived"]])
        self.assertEqual(self.intent_row(ghost["intentId"])["state"],
                         "proposed")
        admitted = self.admit(ghost["intentId"])
        self.assertTrue(admitted["admitted"])

    def test_status_auto_reconciles_ghosts(self):
        owner = self.admit(self.propose(task="task-a")["intentId"])
        ghost = self.propose(agent="codex-b", task="task-b")
        self.call("admit", "--intent", ghost["intentId"])
        self.call("end", "--intent", owner["intentId"], "--fence",
                  owner["fence"], expect=0)
        _, status = self.call("status", expect=0)
        row = [i for i in status["intents"]["items"]
               if i["intentId"] == ghost["intentId"]][0]
        self.assertEqual(row["state"], "superseded")
        self.assertIn(ghost["intentId"],
                      [e["intentId"]
                       for e in status["reconcile"]["superseded"]])

    def test_end_rejects_replay_after_release(self):
        intent = self.propose(task="task-a")
        admitted = self.admit(intent["intentId"])
        self.call("end", "--intent", intent["intentId"], "--fence",
                  admitted["fence"], expect=0)
        proc, row = self.call("end", "--intent", intent["intentId"],
                              "--fence", admitted["fence"])
        self.assertEqual(proc.returncode, 3)
        self.assertIn("not-live", row["reason"])


if __name__ == "__main__":
    unittest.main()
