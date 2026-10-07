"""Exercise journal error/help behaviour against a temporary root only."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("work_journal.py")
SPEC = importlib.util.spec_from_file_location("work_journal", SCRIPT) if SCRIPT.exists() else None
WJ = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    SPEC.loader.exec_module(WJ)


class WorkJournalTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(WJ, "work journal helper is not implemented")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.journal = WJ.open_journal(
            self.root, "test-task-00000000", "test-agent", "synthetic purpose", [])

    def test_invalid_note_kind_lists_allowed_values(self):
        with self.assertRaises(Exception) as caught:
            WJ.add_note(self.root, "test-task-00000000", "applied", "text", [])
        message = str(caught.exception)
        self.assertIn("invalid-event-kind", message)
        for allowed in ("change", "verify", "plan"):
            self.assertIn(allowed, message)

    def test_invalid_close_result_lists_allowed_values(self):
        with self.assertRaises(Exception) as caught:
            WJ.close_journal(self.root, "test-task-00000000", "done", "summary")
        message = str(caught.exception)
        self.assertIn("invalid-close-result", message)
        for allowed in ("verified", "partial", "blocked"):
            self.assertIn(allowed, message)

    def test_valid_note_kind_still_accepted(self):
        journal = WJ.add_note(self.root, "test-task-00000000", "change", "applied", [])
        self.assertEqual("change", journal["events"][-1]["kind"])

    def test_close_with_allowed_result(self):
        journal = WJ.close_journal(self.root, "test-task-00000000", "partial", "wrapped")
        self.assertEqual("partial", journal["result"])
        self.assertEqual("closed", journal["status"])


class WorkJournalEvidenceTest(unittest.TestCase):
    task_id = "test-task-00000000"
    target = "docs/journal-fixture.md"

    def setUp(self):
        WorkJournalTest.setUp(self)

    def cycle(self, name="cycle-1", after=b"tested image\n", exit_code=0, finish=True, targets=None,
              goal_id=None):
        targets = targets or [self.target]
        path = self.root / self.target
        for target in targets:
            fixture = self.root / target
            fixture.parent.mkdir(parents=True, exist_ok=True)
            if not fixture.exists():
                fixture.write_bytes(b"base image\n")
        decision = {"goalId": goal_id or self.task_id, "reasonCode": "synthetic-journal-regression",
                    "risk": {key: 0 for key in WJ.ck.FACTORS},
                    "gates": {key: False for key in WJ.ck.GATES}}
        relative = WJ.BASE + "/" + self.task_id + "/" + name
        WJ.ck.begin(self.root, relative, targets, decision)
        if not finish:
            return self.root / relative
        if after is None:
            path.unlink()
        else:
            path.write_bytes(after)
        WJ.ck.seal(self.root, relative)
        assertion = "True" if exit_code == 0 else "False"
        command = [sys.executable, "-B", "-c",
                   "import unittest\nclass Synthetic(unittest.TestCase):\n"
                   " def test_fixture(self): self.assertTrue(" + assertion + ")\n"
                   "unittest.main()\n"]
        checked = subprocess.run(command, cwd=self.root, capture_output=True, text=True)
        self.assertEqual(exit_code, checked.returncode)
        WJ.ck.finish(self.root, relative, checked.returncode, "synthetic-unittest",
                     checked.stdout + checked.stderr)
        return self.root / relative

    def packet(self):
        return WJ.handoff(self.root, self.task_id, write=False)

    def row(self):
        return self.packet()["sourceCloseEvidence"]["files"][0]

    def close(self, **kwargs):
        return WJ.close_journal(self.root, self.task_id, "verified", "synthetic finalization",
                                touch_status=False, check_evidence=True, **kwargs)

    def journal_bytes(self):
        return (self.root / WJ.BASE / self.task_id / "journal.json").read_bytes()

    def test_prepared_is_unresolved_and_not_run(self):
        self.cycle(finish=False)
        packet = self.packet()
        self.assertEqual("prepared", packet["unresolved"][0]["status"])
        self.assertEqual("NOT_RUN", packet["sourceCloseEvidence"]["status"])
        row = self.row()
        self.assertTrue(row["sourceBindingMatches"])
        self.assertFalse(row["postimageRecorded"])
        with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-close-evidence-NOT_RUN"):
            self.close()

    def test_verified_later_drift_preserves_old_evidence_and_foreign_bytes(self):
        run = self.cycle()
        before = {name: (run / name).read_bytes() for name in ("checkpoint.json", "manifest.json")}
        journal = self.journal_bytes()
        foreign = b"foreign later image\n"
        (self.root / self.target).write_bytes(foreign)
        row = self.row()
        self.assertEqual("verified", row["cycleStatus"])
        self.assertEqual("INVALIDATED", row["verificationStatus"])
        self.assertFalse(row["sourceBindingMatches"])
        with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-close-evidence-INVALIDATED"):
            self.close()
        self.assertEqual(journal, self.journal_bytes())
        self.assertEqual(foreign, (self.root / self.target).read_bytes())
        for name, content in before.items():
            self.assertEqual(content, (run / name).read_bytes())
        self.assertFalse((run.parent / "handoff.json").exists())

    def test_missing_completed_postimage_does_not_mean_deleted(self):
        run = self.cycle(after=None)
        state = json.loads((run / "checkpoint.json").read_bytes())
        del state["postimages"][self.target]
        WJ.ck.write_json(run / "checkpoint.json", state)
        row = self.row()
        self.assertFalse(row["postimageRecorded"])
        self.assertFalse(row["sourceBindingMatches"])
        self.assertNotEqual("PASS", row["verificationStatus"])
        with self.assertRaises(WJ.ck.CheckpointError):
            self.close()

    def test_explicit_null_deleted_postimage_passes_actual_guard(self):
        run = self.cycle(after=None)
        before = (run / "checkpoint.json").read_bytes()
        row = self.row()
        self.assertTrue(row["postimageRecorded"])
        self.assertIsNone(row["postimageSha256"])
        self.assertTrue(row["sourceBindingMatches"])
        self.assertEqual("PASS", row["verificationStatus"])
        result = self.close()
        self.assertEqual("closed", result["status"])
        self.assertEqual("verified", result["result"])
        self.assertEqual(before, (run / "checkpoint.json").read_bytes())

    def test_deleted_postimage_resurrection_is_invalidated(self):
        self.cycle(after=None)
        (self.root / self.target).write_bytes(b"foreign resurrection\n")
        self.assertEqual("INVALIDATED", self.row()["verificationStatus"])
        with self.assertRaises(WJ.ck.CheckpointError):
            self.close()

    def test_unchanged_verified_strict_close_and_idempotent_preserve_bytes(self):
        run = self.cycle()
        checkpoint = (run / "checkpoint.json").read_bytes()
        first = self.close(idempotent=True)
        journal = self.journal_bytes()
        second = self.close(idempotent=True)
        self.assertEqual("closed", first["status"])
        self.assertEqual("verified", second["result"])
        self.assertTrue(second["alreadyClosed"])
        self.assertEqual(journal, self.journal_bytes())
        self.assertEqual(checkpoint, (run / "checkpoint.json").read_bytes())
        self.assertFalse((run.parent / "handoff.json").exists())
        with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-close-result-mismatch"):
            WJ.close_journal(self.root, self.task_id, "partial", "changed", touch_status=False,
                             idempotent=True)
        self.assertEqual(journal, self.journal_bytes())

    def test_goal_id_can_differ_from_journal_task_id(self):
        run = self.cycle(goal_id="independent-goal-00000000")
        before = {name: (run / name).read_bytes() for name in ("checkpoint.json", "manifest.json")}
        row = self.row()
        self.assertTrue(row["manifestBindingMatches"])
        self.assertTrue(row["sourceBindingMatches"])
        self.assertEqual("PASS", row["verificationStatus"])
        result = self.close()
        self.assertEqual("closed", result["status"])
        self.assertEqual("verified", result["result"])
        for name, content in before.items():
            self.assertEqual(content, (run / name).read_bytes())
        self.assertFalse((run.parent / "handoff.json").exists())

    def test_strict_idempotent_retry_rechecks_current_source(self):
        self.cycle()
        self.close(idempotent=True)
        journal = self.journal_bytes()
        (self.root / self.target).write_bytes(b"foreign after close\n")
        with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-close-evidence-INVALIDATED"):
            self.close(idempotent=True)
        self.assertEqual(journal, self.journal_bytes())

    def test_guard_rejection_keeps_journal_and_current_source(self):
        self.cycle()
        WJ.open_journal(self.root, "foreign-task-00000000", "other-agent", "synthetic overlap",
                        [self.target])
        journal = self.journal_bytes()
        source = (self.root / self.target).read_bytes()
        with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-close-guard-rejected"):
            self.close()
        self.assertEqual(journal, self.journal_bytes())
        self.assertEqual(source, (self.root / self.target).read_bytes())

    def test_current_hash_rechecked_after_actual_guard(self):
        self.cycle()
        journal = self.journal_bytes()
        real_run = WJ.subprocess.run
        calls = []
        def drift_after_guard(command, **kwargs):
            checked = real_run(command, **kwargs)
            calls.append(checked.returncode)
            (self.root / self.target).write_bytes(b"foreign during final guard\n")
            return checked
        with patch.object(WJ.subprocess, "run", side_effect=drift_after_guard):
            with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-close-evidence-changed"):
                self.close()
        self.assertEqual([0], calls)
        self.assertEqual(journal, self.journal_bytes())
        self.assertEqual(b"foreign during final guard\n", (self.root / self.target).read_bytes())

    def test_receipt_rechecked_after_actual_guard_even_when_source_unchanged(self):
        run = self.cycle()
        journal = self.journal_bytes()
        source = (self.root / self.target).read_bytes()
        real_run = WJ.subprocess.run
        calls = []
        def receipt_changed_after_guard(command, **kwargs):
            checked = real_run(command, **kwargs)
            calls.append(checked.returncode)
            state = json.loads((run / "checkpoint.json").read_bytes())
            state["commandId"] = "changed-after-guard"
            WJ.ck.save(run, state)
            return checked
        with patch.object(WJ.subprocess, "run", side_effect=receipt_changed_after_guard):
            with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-close-evidence-changed"):
                self.close()
        self.assertEqual([0], calls)
        self.assertEqual(journal, self.journal_bytes())
        self.assertEqual(source, (self.root / self.target).read_bytes())

    def test_latest_path_selection_uses_checkpoint_time_not_cycle_name(self):
        old = self.cycle(name="z-old", after=b"older verified\n")
        old_bytes = (old / "checkpoint.json").read_bytes()
        new = self.cycle(name="a-new", after=b"latest verified\n")
        row = self.row()
        self.assertEqual("a-new", row["cycle"])
        self.assertEqual("PASS", row["verificationStatus"])
        self.close()
        self.assertEqual(old_bytes, (old / "checkpoint.json").read_bytes())
        self.assertEqual("verified", json.loads((new / "checkpoint.json").read_bytes())["status"])

    def test_latest_prepared_cannot_inherit_old_pass(self):
        self.cycle(name="z-old")
        self.cycle(name="a-new", finish=False)
        row = self.row()
        self.assertEqual("a-new", row["cycle"])
        self.assertEqual("NOT_RUN", row["verificationStatus"])
        self.assertTrue(row["sourceBindingMatches"])
        with self.assertRaises(WJ.ck.CheckpointError):
            self.close()

    def test_latest_per_path_retains_other_older_verified_file(self):
        other = "docs/other-fixture.md"
        old = self.cycle(name="z-old", targets=[self.target, other])
        previous = (old / "checkpoint.json").read_bytes()
        self.cycle(name="a-new", after=b"newer tested image\n")
        rows = {row["path"]: row for row in self.packet()["sourceCloseEvidence"]["files"]}
        self.assertEqual("a-new", rows[self.target]["cycle"])
        self.assertEqual("z-old", rows[other]["cycle"])
        self.assertEqual({"PASS"}, {row["verificationStatus"] for row in rows.values()})
        self.close()
        self.assertEqual(previous, (old / "checkpoint.json").read_bytes())

    def test_pending_cycle_does_not_hide_current_invalidation(self):
        self.cycle(finish=False)
        (self.root / self.target).write_bytes(b"unsealed current change\n")
        packet = self.packet()
        self.assertEqual("prepared", packet["unresolved"][0]["status"])
        self.assertEqual("INVALIDATED", packet["sourceCloseEvidence"]["status"])

    def test_failed_and_held_cycles_do_not_promote_to_verified(self):
        run = self.cycle(exit_code=1)
        self.assertEqual("FAIL", self.row()["verificationStatus"])
        with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-close-evidence-FAIL"):
            self.close()
        state = json.loads((run / "checkpoint.json").read_bytes())
        state["status"] = "hold"
        state["firstBlockingRule"] = "synthetic-hold"
        WJ.ck.write_json(run / "checkpoint.json", state)
        self.assertEqual("HOLD", self.row()["verificationStatus"])
        with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-close-evidence-HOLD"):
            self.close()

    def test_no_execution_evidence_stays_not_run(self):
        run = self.cycle()
        state = json.loads((run / "checkpoint.json").read_bytes())
        del state["verificationExitCode"]
        WJ.ck.write_json(run / "checkpoint.json", state)
        self.assertEqual("NOT_RUN", self.row()["verificationStatus"])
        with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-close-evidence-NOT_RUN"):
            self.close()

    def test_default_close_remains_report_only_and_non_idempotent(self):
        result = WJ.close_journal(self.root, self.task_id, "verified", "legacy report",
                                  touch_status=False)
        self.assertEqual("verified", result["result"])
        with self.assertRaisesRegex(WJ.ck.CheckpointError, "journal-already-closed"):
            WJ.close_journal(self.root, self.task_id, "verified", "legacy retry", touch_status=False)


if __name__ == "__main__":
    unittest.main()
