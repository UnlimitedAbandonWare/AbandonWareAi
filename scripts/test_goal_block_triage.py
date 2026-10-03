"""Tests for goal_block_triage.py — Codex goal blocked-loop triage.

Fixtures under scripts/fixtures/goal_block/ are 1-3 sentence excerpts of the
five 2026-10-03 blocked sessions (secrets stripped); expected classifications
come from the live audit (F1).
"""
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
FIX = HERE / "fixtures" / "goal_block"
SCRIPT = HERE / "goal_block_triage.py"
SPEC = importlib.util.spec_from_file_location("goal_block_triage", SCRIPT)
GBT = importlib.util.module_from_spec(SPEC)
sys.modules.setdefault("goal_block_triage", GBT)
SPEC.loader.exec_module(GBT)

ATTACH_ROOT = FIX / "attachments"


def triage(name, leases=None, coop=True):
    info = GBT.parse_rollout(FIX / name)
    return GBT.triage_session(info, audits=[], leases=leases or [],
                              coop_fixed=coop, attachments_root=ATTACH_ROOT,
                              root=HERE.parent)


class ClassificationTest(unittest.TestCase):
    def test_1149_unmeasurable(self):
        row = triage("rollout_1149_unmeasurable.jsonl")
        self.assertEqual(row["status"], "BLOCKED_LOOP")
        self.assertEqual(set(row["labels"]), {"UNMEASURABLE_EVIDENCE"})
        self.assertEqual(row["verdict"], "BLOCKED_EXTERNAL")
        self.assertIn("HOLD", row["paste_line"])

    def test_1211_unmeasurable_and_lease(self):
        row = triage("rollout_1211_unmeas_lease.jsonl")
        self.assertEqual(row["status"], "BLOCKED_LOOP")
        self.assertTrue({"UNMEASURABLE_EVIDENCE", "LEASE_HELD"}
                        <= set(row["labels"]))

    def test_1243_tool_limit_resumable(self):
        row = triage("rollout_1243_tool_limit.jsonl", coop=True)
        self.assertEqual(row["status"], "BLOCKED_LOOP")
        self.assertIn("TOOL_LIMIT", row["labels"])
        self.assertEqual(row["verdict"], "RESUMABLE_NOW")
        self.assertIn("풀렸어", row["paste_line"])
        self.assertIn("writer-cap", row["tool_note"])

    def test_1316_lease_and_unmeasurable(self):
        row = triage("rollout_1316_lease_unmeas.jsonl")
        self.assertEqual(row["status"], "BLOCKED_LOOP")
        self.assertTrue({"LEASE_HELD", "UNMEASURABLE_EVIDENCE"}
                        <= set(row["labels"]))

    def test_1505_contradictory_acceptance(self):
        row = triage("rollout_1505_contradictory.jsonl")
        self.assertEqual(row["status"], "BLOCKED_LOOP")
        self.assertIn("CONTRADICTORY_ACCEPTANCE", row["labels"])
        self.assertEqual(row["verdict"], "NEEDS_DIRECTIVE_FIX")
        self.assertIn("PARTIAL", row["paste_line"])
        self.assertIn("완료", row["paste_line"])

    def test_running_not_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "rollout_running.jsonl"
            p.write_text((FIX / "rollout_running.jsonl").read_text(
                encoding="utf-8"), encoding="utf-8")
            os.utime(p, None)
            info = GBT.parse_rollout(p)
            row = GBT.triage_session(info, [], [], True, ATTACH_ROOT, HERE.parent)
        self.assertEqual(row["status"], "RUNNING")
        self.assertNotIn("BLOCKED", row["status"])

    def test_subagent_skipped(self):
        row = triage("rollout_subagent.jsonl")
        self.assertEqual(row.get("skipped"), "subagent")


class RedactionTest(unittest.TestCase):
    def test_secret_patterns_redacted(self):
        row = triage("rollout_secret.jsonl")
        blob = json.dumps(row, ensure_ascii=False)
        self.assertNotIn("sk-FAKEKEY1234567890abcd", blob)
        self.assertNotIn("FAKEbearerToken999999", blob)
        self.assertIn("[REDACTED]", blob)


class MainSmokeTest(unittest.TestCase):
    def test_rollout_arg_and_json(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = GBT.main(["--rollout", str(FIX / "rollout_1149_unmeasurable.jsonl"),
                           "--audit-dir", "", "--json"])
        self.assertEqual(rc, 0)
        out = json.loads(buf.getvalue())
        self.assertEqual(out["sessions"][0]["status"], "BLOCKED_LOOP")
        self.assertNotIn("sk-", buf.getvalue())

    def test_date_mode_scans_day_dir(self):
        with tempfile.TemporaryDirectory() as td:
            day = Path(td) / "2026" / "10" / "03"
            day.mkdir(parents=True)
            for src in FIX.glob("rollout_*.jsonl"):
                (day / src.name).write_text(
                    src.read_text(encoding="utf-8"), encoding="utf-8")
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = GBT.main(["--date", "2026-10-03", "--sessions-dir", td,
                               "--audit-dir", "", "--json"])
            self.assertEqual(rc, 0)
            out = json.loads(buf.getvalue())
            self.assertEqual(out["skipped_subagent"], 1)
            blocked = [s for s in out["sessions"]
                       if s.get("status", "").startswith("BLOCKED")]
            loops = [s for s in out["sessions"]
                     if s.get("status") == "BLOCKED_LOOP"]
            self.assertEqual(len(blocked), 6)  # 5 F1 sessions + secret fixture
            self.assertEqual(len(loops), 5)


if __name__ == "__main__":
    unittest.main()
