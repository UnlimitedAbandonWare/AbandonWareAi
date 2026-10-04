"""Synthetic three-branch checks for checkpoint_fp_autoflow decisions."""
import json
from pathlib import Path
import tempfile
import unittest

from scripts import checkpoint_fp_autoflow as AF


def hit(kind, blocking=True):
    return {"line": 1, "identifier": "to" + "ken", "rhsKind": kind, "blocking": blocking}


class ClassifyBranchTest(unittest.TestCase):

    def test_call_shaped_hits_go_scanner_fix(self):
        kinds = [{"call"}, {"name"}, {"attr"}, {"none_bool"}]
        for group in kinds:
            hits = [hit(k) for k in group]
            with self.subTest(kinds=group):
                self.assertEqual("scanner_fix", AF.classify(hits, None, False))

    def test_lease_or_forbidden_brief_queues_the_fix(self):
        hits = [hit("call")]
        self.assertEqual("scanner_fix_queued", AF.classify(hits, "other-topic", False))
        self.assertEqual("scanner_fix_queued", AF.classify(hits, None, True))

    def test_literal_and_mixed_hits_are_secret_suspects(self):
        self.assertEqual("real_secret_suspect", AF.classify([hit("literal")], None, False))
        self.assertEqual("real_secret_suspect",
                         AF.classify([hit("call"), hit("literal")], None, False))
        self.assertEqual("real_secret_suspect", AF.classify([hit("other")], None, False))
        self.assertEqual("real_secret_suspect", AF.classify([], None, False))


class DecideEndToEndTest(unittest.TestCase):

    tok = "to" + "ken"

    def fixture(self, root):
        path = Path(root) / "docs"
        path.mkdir(parents=True, exist_ok=True)
        (path / "note.md").write_text(self.tok + " = make_lock_token()\n", encoding="utf-8")
        return "docs/note.md"

    def test_scanner_fix_decision(self):
        with tempfile.TemporaryDirectory() as root:
            out = AF.decide(self.fixture(root), root)
            self.assertEqual("scanner_fix", out["decision"])
            self.assertNotIn("ticket", out)

    def test_queued_writes_exactly_one_ticket(self):
        with tempfile.TemporaryDirectory() as root:
            rel = self.fixture(root)
            brief = Path(root) / "brief.md"
            brief.write_text("변경 금지 목록: scripts/codex_work_checkpoint.py 그 외 자유\n",
                             encoding="utf-8")
            out = AF.decide(rel, root, brief=str(brief))
            self.assertEqual("scanner_fix_queued", out["decision"])
            tickets = list((Path(root) / AF.QUEUE_DIR).glob("*.json"))
            self.assertEqual(1, len(tickets))
            body = json.loads(tickets[0].read_text(encoding="utf-8"))
            self.assertEqual(rel, body["path"])
            self.assertEqual("call", body["hits"][0]["rhsKind"])
            self.assertNotIn("match", body["hits"][0])

    def test_foreign_lease_also_queues(self):
        with tempfile.TemporaryDirectory() as root:
            rel = self.fixture(root)
            lock = Path(root) / "__patch_drop__" / "source-edit-locks" / "other.lock"
            lock.mkdir(parents=True)
            (lock / "lease.json").write_text(json.dumps({
                "status": "active", "ownerId": "someone-else",
                "targetPaths": ["scripts/codex_work_checkpoint.py"]}), encoding="utf-8")
            out = AF.decide(rel, root, owner="devin-me")
            self.assertEqual("scanner_fix_queued", out["decision"])
            self.assertTrue(out["reason"].startswith("foreign-lease:"))

    def test_literal_shape_is_suspect_and_writes_nothing(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "scripts"
            path.mkdir(parents=True)
            (path / "e.py").write_text(self.tok + ' = "sk-' + 'D' * 24 + '"\n', encoding="utf-8")
            out = AF.decide("scripts/e.py", root)
            self.assertEqual("real_secret_suspect", out["decision"])
            self.assertFalse((Path(root) / AF.QUEUE_DIR).exists())


if __name__ == "__main__":
    unittest.main()
