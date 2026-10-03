#!/usr/bin/env python3
"""Synthetic fixtures for devin_brief_lint. Stdlib only."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import devin_brief_lint as lint  # noqa: E402


GOOD = """[ANTI-STOP] 읽고 끝까지 하세요.
Set-Location C:\\AbandonWare\\demo-1\\demo-1\\src

한 줄 목표
scripts\\devin_brief_lint.py 검사기를 추가합니다.

수정 허용
- scripts\\devin_brief_lint.py
- scripts\\test_devin_brief_lint.py

절대 금지
push, commit, 전체 테스트, 비밀값, chat.js

G0 준비
G1 구현
G2 테스트
G3 보고
G4 정리
G5 기록

Acceptance
A1 PASS 조건. 안 돌린 것은 NOT_RUN.

보고 첫 줄은 외부 API: 입니다.
ledger: data\\agent-handoff\\devin-example\\
[ANTI-STOP] 계획만 쓰고 멈추지 마세요.
"""

HANDOFF = """전체 개선을 알아서 전부 해 주세요.
설계도 새로 해 주세요.
"""

SPLIT = GOOD.replace(
    "G5 기록",
    "\n".join("WP%s 단계" % n for n in range(12)),
)


class BriefLintTests(unittest.TestCase):
    def test_good(self):
        result = lint.lint_text(GOOD)
        self.assertEqual(result["fit"], "GOOD")
        self.assertEqual(result["missing"], [])
        self.assertEqual(result["stepCount"], 6)

    def test_handoff_without_files(self):
        result = lint.lint_text(HANDOFF)
        self.assertEqual(result["fit"], "HANDOFF")
        self.assertIn("unbounded_scope", result["missing"])
        self.assertTrue(result["suggestions"])

    def test_split_when_too_many_steps(self):
        result = lint.lint_text(SPLIT)
        self.assertEqual(result["fit"], "SPLIT")
        self.assertGreater(result["stepCount"], 10)
        self.assertIn("step_count", result["missing"])

    def test_split_when_bottom_anti_stop_missing(self):
        text = GOOD.replace("[ANTI-STOP] 계획만 쓰고 멈추지 마세요.\n", "")
        result = lint.lint_text(text)
        self.assertEqual(result["fit"], "SPLIT")
        self.assertIn("anti_stop_bottom", result["missing"])

    def test_set_location_must_be_first_command(self):
        text = GOOD.replace(
            "Set-Location C:\\AbandonWare\\demo-1\\demo-1\\src",
            "python -B scripts\\devin_brief_lint.py\nSet-Location C:\\AbandonWare\\demo-1\\demo-1\\src",
            1,
        )
        result = lint.lint_text(text)
        self.assertIn("set_location_first", result["missing"])

    def test_phrase_with_file_list_is_not_handoff(self):
        text = GOOD.replace("한 줄 목표", "한 줄 목표\n전체를 다 고쳐 달라는 말은 쓰지 않습니다.", 1)
        result = lint.lint_text(text)
        self.assertEqual(result["fit"], "GOOD")


if __name__ == "__main__":
    unittest.main()
