"""Tests for goal_block_triage.py SCOPE_AMBIGUITY label.

Regression case: data/agent-handoff/codex-git-ship-easy-unblock-5bd4a1b9/
SHIP_REPORT.md:63-72 — Codex widened an unenumerated "protected file" phrase
into a whole-file protection inference and stopped on the same blocker for a
third consecutive goal turn. The label must map to RESUMABLE_NOW (the scope
interpretation rule resolves it) with a paste line naming the rule — never
NEEDS_DIRECTIVE_FIX.
"""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
FIX = HERE / "fixtures" / "goal_block"
SCRIPT = HERE / "goal_block_triage.py"
SPEC = importlib.util.spec_from_file_location("goal_block_triage", SCRIPT)
GBT = importlib.util.module_from_spec(SPEC)
sys.modules.setdefault("goal_block_triage", GBT)
SPEC.loader.exec_module(GBT)

ATTACH_ROOT = FIX / "attachments"

SCOPE_AUDIT_TEXT = """## Final blocked audit: A7 scope correction
A7 scope unresolved: whether the whole file or only the detection rules are
protected is 미정. The whole-file protection inference was corrected; the
objective's protected scope enumeration is absent. protected scope 미정 —
A7 cannot be claimed until the 보호 범위가 정의된다.
"""


def audit_row_from_text(text):
    with tempfile.TemporaryDirectory() as td:
        d = Path(td)
        (d / "blocked-audit-scope.md").write_text(text, encoding="utf-8")
        audits = GBT.load_audits([d])
        assert len(audits) == 1
        return GBT.audit_row(audits[0], [])


class ScopeAmbiguityLabelTest(unittest.TestCase):
    def test_scope_phrases_get_label(self):
        labels = GBT.classify(
            "A7 scope unresolved — whole-file protection inference, "
            "protected scope 미정")
        self.assertIn("SCOPE_AMBIGUITY", labels)

    def test_korean_scope_phrase_gets_label(self):
        labels = GBT.classify("보호 범위가 미정이라 멈췄다. 범위 해석이 필요하다")
        self.assertIn("SCOPE_AMBIGUITY", labels)

    def test_audit_row_resumable_with_rule_pointer(self):
        row = audit_row_from_text(SCOPE_AUDIT_TEXT)
        self.assertIn("SCOPE_AMBIGUITY", row["labels"])
        self.assertEqual(row["verdict"], "RESUMABLE_NOW")
        self.assertIn("보호 범위 해석", row["paste_line"])

    def test_contradictory_fixture_unchanged(self):
        info = GBT.parse_rollout(FIX / "rollout_1505_contradictory.jsonl")
        row = GBT.triage_session(info, audits=[], leases=[], coop_fixed=True,
                                 attachments_root=ATTACH_ROOT,
                                 root=HERE.parent)
        self.assertEqual(row["status"], "BLOCKED_LOOP")
        self.assertIn("CONTRADICTORY_ACCEPTANCE", row["labels"])
        self.assertNotIn("SCOPE_AMBIGUITY", row["labels"])
        self.assertEqual(row["verdict"], "NEEDS_DIRECTIVE_FIX")

    def test_lease_only_text_not_scope(self):
        labels = GBT.classify(
            "다른 세션 lease가 scripts/foo.py를 예약 중이라 멈춤")
        self.assertIn("LEASE_HELD", labels)
        self.assertNotIn("SCOPE_AMBIGUITY", labels)

    def test_deep_scan_finds_report_blocked_audit(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            (d / "SHIP_REPORT.md").write_text(
                "## Final blocked audit\n" + SCOPE_AUDIT_TEXT,
                encoding="utf-8")
            self.assertEqual(GBT.load_audits([d]), [])
            audits = GBT.load_audits([d], deep=True)
            self.assertEqual(len(audits), 1)
            row = GBT.audit_row(audits[0], [])
            self.assertIn("SCOPE_AMBIGUITY", row["labels"])
            self.assertEqual(row["verdict"], "RESUMABLE_NOW")


if __name__ == "__main__":
    unittest.main()
