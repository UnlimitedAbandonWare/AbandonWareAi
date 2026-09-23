"""Scope coordinator tests; every mutation happens inside a temporary root."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "agent_scope_lease.py"


class AgentScopeLeaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-scope-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "__patch_drop__").mkdir()
        (self.root / "target.txt").write_text("user preimage\n")
        (self.root / "other.txt").write_text("other file\n")
        (self.root / "data").mkdir()
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

    def claim(self, agent="devin", task=None, extra=()):
        args = ["claim", "--agent", agent, "--path", "target.txt",
                "--purpose", "synthetic claim"]
        if task:
            args += ["--task", task]
        return self.call(*(args + list(extra)), expect=0)[1]

    def test_check_free_path_is_allowed(self):
        proc, row = self.call("check", "--path", "target.txt", expect=0)
        self.assertTrue(row["allowed"])
        self.assertEqual(row["leaseConflict"]["conflictingLeaseCount"], 0)

    def test_claim_who_done_cycle(self):
        claimed = self.claim(extra=["--feature", "nova-focus"])
        self.assertTrue(claimed["acquired"])
        task_id = claimed["taskId"]
        _, who = self.call("who", expect=0)
        mine = [c for c in who["claims"] if c["taskId"] == task_id]
        self.assertEqual(len(mine), 1)
        self.assertEqual(mine[0]["features"], ["nova-focus"])
        self.assertFalse(mine[0]["released"])
        proc, blocked = self.call("check", "--path", "target.txt")
        self.assertEqual(proc.returncode, 7)
        self.assertFalse(blocked["allowed"])
        self.assertIn("target.txt", blocked["leaseConflict"]["conflictingPaths"])
        proc, done = self.call("done", "--task", task_id, expect=0)
        self.assertTrue(done["released"])
        _, after = self.call("check", "--path", "target.txt", expect=0)
        self.assertTrue(after["allowed"])

    def test_second_writer_claim_on_same_path_is_rejected(self):
        self.claim(agent="codex")
        proc, row = self.call("claim", "--agent", "devin", "--path", "target.txt")
        self.assertEqual(proc.returncode, 7)
        self.assertFalse(row["acquired"])

    def test_disjoint_claims_coexist(self):
        first = self.claim(agent="codex")
        second = self.call("claim", "--agent", "devin", "--path", "other.txt",
                          "--purpose", "disjoint claim", expect=0)[1]
        self.assertTrue(second["acquired"])
        self.assertNotEqual(first["taskId"], second["taskId"])
        self.call("done", "--task", first["taskId"], expect=0)
        self.call("done", "--task", second["taskId"], expect=0)

    def test_feature_overlap_is_advisory_not_block(self):
        self.claim(agent="codex", extra=["--feature", "hint-paging"])
        proc, row = self.call("check", "--path", "other.txt",
                             "--feature", "hint-paging", expect=0)
        self.assertTrue(row["allowed"])
        kinds = {a["kind"] for a in row["advisories"]}
        self.assertIn("claim-overlap", kinds)
        hits = [a for a in row["advisories"] if a["kind"] == "claim-overlap"]
        self.assertEqual(hits[0]["features"], ["hint-paging"])

    def test_verify_detects_foreign_drift(self):
        claimed = self.claim()
        (self.root / "target.txt").write_text("foreign change\n")
        proc, row = self.call("verify", "--task", claimed["taskId"])
        self.assertNotEqual(proc.returncode, 0)
        self.assertFalse(row["verified"])
        self.call("abort", "--task", claimed["taskId"], expect=0)

    def test_abort_releases_and_marks_claim(self):
        claimed = self.claim()
        proc, row = self.call("abort", "--task", claimed["taskId"], expect=0)
        self.assertTrue(row["released"])
        _, shown = self.call("show", "--task", claimed["taskId"], expect=0)
        self.assertTrue(shown["claims"][0]["claim"]["released"])
        self.assertEqual(shown["claims"][0]["claim"]["releaseReason"], "abort")

    def test_heartbeat_renews(self):
        claimed = self.claim()
        proc, row = self.call("heartbeat", "--task", claimed["taskId"], expect=0)
        self.assertTrue(row["renewed"])
        self.call("done", "--task", claimed["taskId"], expect=0)

    def test_attach_to_existing_task_releases_all_claims(self):
        first = self.claim(agent="codex")
        proc, second = self.call("claim", "--agent", "devin",
                                "--task", first["taskId"], "--topic", "second-scope",
                                "--path", "other.txt", expect=0)
        self.assertTrue(second["acquired"])
        self.assertEqual(second["taskId"], first["taskId"])
        _, shown = self.call("show", "--task", first["taskId"], expect=0)
        self.assertEqual(len(shown["claims"]), 2)
        proc, aborted = self.call("abort", "--task", first["taskId"], expect=0)
        self.assertEqual(sorted(aborted["topics"]), sorted([first["topic"], "second-scope"]))
        _, shown = self.call("show", "--task", first["taskId"], expect=0)
        self.assertTrue(all(c["claim"]["released"] for c in shown["claims"]))


if __name__ == "__main__":
    unittest.main()
