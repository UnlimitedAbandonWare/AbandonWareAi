"""Exercise journal error/help behaviour against a temporary root only."""
import importlib.util
from pathlib import Path
import tempfile
import unittest


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


if __name__ == "__main__":
    unittest.main()
