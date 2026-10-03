#!/usr/bin/env python3
"""test_brief_round_gate.py — brief_round_gate.py 테스트. python -B scripts/test_brief_round_gate.py

재사용한 기존 스크립트: brief_round_gate.py (gate, parse_verdicts)
새로 추가한 것: PENDING / OPEN_NEXT / BLOCKED / NOT_RUN 사유 규칙 픽스처
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import brief_round_gate  # noqa: E402

MANIFEST = {
    "agents": {
        "codex": {
            "reportDir": "rep",
            "rounds": {
                "R1": {"acceptance": ["A1", "A2"], "card": "cards/R1.txt", "next": "R2"},
                "R2": {"acceptance": ["A3"], "card": "cards/R2.txt", "next": None},
            },
        }
    }
}

REPORT_OK = """# REPORT-R1
A1: PASS — 4 tests green
- A2 NOT_RUN(사유: Codex fixture 부재로 재현 불가, 근거 로그 첨부)
"""

REPORT_FAIL = """# REPORT-R1
A1: FAIL — test still red
A2: PASS
"""

REPORT_MISSING_ID = """# REPORT-R1
A1: PASS
"""

REPORT_NOTRUN_NO_REASON = """# REPORT-R1
A1: PASS
A2: NOT_RUN
"""


class RoundGateTest(unittest.TestCase):
    def _setup(self, d: Path):
        manifest = d / "rounds.json"
        manifest.write_text(json.dumps(MANIFEST), encoding="utf-8")
        (d / "rep").mkdir()
        return manifest

    def test_pending_when_no_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            manifest = self._setup(d)
            msg, code = brief_round_gate.gate("codex", "R1", root=d, manifest_path=manifest,
                                              report_override=None)
            self.assertEqual(code, 2)
            self.assertTrue(msg.startswith("PENDING"))

    def test_open_next_on_all_pass_or_reasoned_notrun(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            manifest = self._setup(d)
            rep = d / "rep" / "REPORT-R1.md"
            rep.write_text(REPORT_OK, encoding="utf-8")
            msg, code = brief_round_gate.gate("codex", "R1", root=d, manifest_path=manifest,
                                              report_override=None)
            self.assertEqual(code, 0, msg)
            self.assertIn("cards/R2.txt", msg)

    def test_blocked_on_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            manifest = self._setup(d)
            rep = d / "rep" / "REPORT-R1.md"
            rep.write_text(REPORT_FAIL, encoding="utf-8")
            msg, code = brief_round_gate.gate("codex", "R1", root=d, manifest_path=manifest,
                                              report_override=None)
            self.assertEqual(code, 1)
            self.assertIn("A1", msg)

    def test_blocked_on_missing_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            manifest = self._setup(d)
            rep = d / "rep" / "REPORT-R1.md"
            rep.write_text(REPORT_MISSING_ID, encoding="utf-8")
            msg, code = brief_round_gate.gate("codex", "R1", root=d, manifest_path=manifest,
                                              report_override=None)
            self.assertEqual(code, 1)
            self.assertIn("A2", msg)

    def test_notrun_without_reason_blocked(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            manifest = self._setup(d)
            rep = d / "rep" / "REPORT-R1.md"
            rep.write_text(REPORT_NOTRUN_NO_REASON, encoding="utf-8")
            msg, code = brief_round_gate.gate("codex", "R1", root=d, manifest_path=manifest,
                                              report_override=None)
            self.assertEqual(code, 1)
            self.assertIn("A2", msg)

    def test_last_round_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            manifest = self._setup(d)
            rep = d / "rep" / "REPORT-R2.md"
            rep.write_text("A3: PASS\n", encoding="utf-8")
            msg, code = brief_round_gate.gate("codex", "R2", root=d, manifest_path=manifest,
                                              report_override=None)
            self.assertEqual(code, 0, msg)
            self.assertIn("COMPLETE", msg)


if __name__ == "__main__":
    unittest.main(verbosity=2)
