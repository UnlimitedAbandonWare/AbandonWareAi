#!/usr/bin/env python3
"""test_staged_method_check.py — staged_method_check.py 유닛테스트 (읽기 전용).

합성 짧은 텍스트만 사용 — live tree·네트워크 무접촉.
실행: python -B scripts/test_staged_method_check.py
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "staged_method_check.py"

GOOD = """# PASTE_CODEX_TEST_20261005

## 0. 한 줄 목표
foo.py 로깅 한 줄 추가.

## 1. 사실
- scripts/foo.py:12 현재 로그 없음 확인. 나머지는 확인 필요 항목으로 표기.

## 작업 단계
### W0. 준비
- python -B scripts/demo1_vibe_skill_router.py resolve "로깅 추가"
### W1. 수정
- scripts/foo.py 최소 수정. primary skill: @demo1-work-ledger

## HOLD
- 라이브 호출 금지. 보고 후 새 세션에서 다음 목표.

## Acceptance
- A1 단위 테스트 PASS
- A2 안 돌린 항목은 NOT_RUN
"""


def run_check(*args, stdin_text=None):
    cmd = [sys.executable, "-B", str(SCRIPT), *args]
    return subprocess.run(cmd, capture_output=True, text=True,
                          input=stdin_text, encoding="utf-8",
                          errors="replace", timeout=60)


class StagedMethodCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="staged-method-"))
        self.brief = self.tmp / "PASTE_CODEX_TEST_20261005.md"

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_brief(self, text, *extra):
        self.brief.write_text(text, encoding="utf-8")
        return run_check("--brief", str(self.brief), *extra)

    def test_good_brief_pass(self):
        r = self.run_brief(GOOD)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("VERDICT: PASS", r.stdout)
        self.assertIn("G1-G6 present", r.stdout)

    def test_no_skill_flagged(self):
        bad = GOOD.replace("python -B scripts/demo1_vibe_skill_router.py resolve \"로깅 추가\"", "준비만 한다.")
        bad = bad.replace("primary skill: @demo1-work-ledger", "수정한다.")
        r = self.run_brief(bad)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("G2", r.stdout)

    def test_no_facts_flagged(self):
        bad = GOOD.replace("scripts/foo.py:12 현재 로그 없음 확인. 나머지는 확인 필요 항목으로 표기.",
                           "대충 아는 대로 한다.")
        r = self.run_brief(bad)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("G3", r.stdout)

    def test_single_stage_flagged(self):
        bad = GOOD.replace("### W1. 수정\n- scripts/foo.py 최소 수정. primary skill: @demo1-work-ledger",
                           "- scripts/foo.py 수정. @demo1-work-ledger")
        bad = bad.replace("## 작업 단계", "## 항목").replace("단계형으로", "")
        r = self.run_brief(bad)
        self.assertIn("G4", r.stdout)

    def test_scatter_fails(self):
        scatter = ("# X\n한 줄 목표: 한다.\n사실: a.py:1 확인 필요.\n"
                   "@demo1-one @demo1-two @demo1-three @demo1-four @demo1-five\n"
                   "단계1 단계2. HOLD 후 새 세션.\nAcceptance: PASS\n")
        r = self.run_brief(scatter)
        self.assertEqual(r.returncode, 2, r.stdout)
        self.assertIn("SKILL_SCATTER", r.stdout)

    def test_scatter_with_resolve_not_fail(self):
        scatter = ("# X\n한 줄 목표: 한다.\n사실: a.py:1 확인 필요.\n"
                   "python -B scripts/demo1_vibe_skill_router.py resolve \"x\"\n"
                   "@demo1-one @demo1-two @demo1-three @demo1-four @demo1-five\n"
                   "단계1 단계2. HOLD 후 새 세션.\nAcceptance: PASS\n")
        r = self.run_brief(scatter)
        self.assertNotEqual(r.returncode, 2, r.stdout)

    def test_no_acceptance_flagged(self):
        bad = GOOD.replace("## Acceptance", "## 마무리").replace("PASS", "OK").replace("NOT_RUN", "SKIP")
        r = self.run_brief(bad)
        self.assertIn("G5", r.stdout)

    def test_three_missing_fails(self):
        minimal = "# 메모\n뭔가 한다.\n"
        r = self.run_brief(minimal)
        self.assertEqual(r.returncode, 2)
        self.assertIn("VERDICT: FAIL", r.stdout)

    def test_text_stdin(self):
        r = run_check("--text", "-", stdin_text=GOOD)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_json_schema(self):
        r = self.run_brief(GOOD, "--json")
        self.assertEqual(r.returncode, 0)
        payload = json.loads(r.stdout)
        self.assertEqual(payload["schemaVersion"], "awx.staged-method-check.v1")
        self.assertEqual(payload["verdict"], "PASS")
        self.assertFalse(payload["missing"])

    def test_missing_file_exit3(self):
        r = run_check("--brief", str(self.tmp / "absent.md"))
        self.assertEqual(r.returncode, 3)


if __name__ == "__main__":
    unittest.main(verbosity=2)
