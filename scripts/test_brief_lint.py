#!/usr/bin/env python3
"""test_brief_lint.py — brief_lint.py 테스트. python -B scripts/test_brief_lint.py

재사용한 기존 스크립트: brief_lint.py (lint_text)
새로 추가한 것: 가짜 지시서 픽스처 8종(PASS 1 / FAIL 4 / WARN 3)
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import brief_lint  # noqa: E402


def _ids(result: dict) -> set[str]:
    return {f["id"] for f in result["findings"]}


GOOD_BRIEF = """# DEVIN 지시서 R1: 작은 카드 (테스트용)
@objective-executor @agent-scope-lease

Project Root: C:\\AbandonWare\\demo-1\\demo-1\\src
COMMON_RULES: .agents/rules/demo1-common-brief-rules.md 적용

## 작업
### WP1 첫 번째 일
- 내용
### WP2 두 번째 일
- 내용
### WP3 세 번째 일
- 내용

## Acceptance
- A1: WP1 PASS 또는 사유 있는 NOT_RUN
- A2: WP2 PASS 또는 사유 있는 NOT_RUN
- A3: WP3 PASS 또는 사유 있는 NOT_RUN
실행 못 한 항목은 NOT_RUN + 이유. NOT_RUN을 PASS로 바꾸지 않는다.

[ANTI-STOP] A1~A3을 모두 판정하기 전에는 종료하지 마라.
"""

NO_ANTI_STOP = """# 지시서
## Acceptance
- A1: WP1 확인
NOT_RUN 규칙 포함.
### WP1 일
"""

NO_ACCEPTANCE = """# 지시서
### WP1 일
[ANTI-STOP] 끝까지.
NOT_RUN 표기.
"""

UNCOVERED_PACKAGE = """# 지시서
### WP1 일
### WP2 다른 일
## Acceptance
- A1: WP1 만 확인
NOT_RUN 표기.
[ANTI-STOP] 계속.
"""

DEVIN_NO_SKILL = """# DEVIN 지시서
Project Root: x
### D1 일
## Acceptance
- B1: D1 확인
NOT_RUN 표기.
[ANTI-STOP] 계속.
"""

WITH_SECRET = """# 지시서
### WP1 일
키는 sk-abcdef1234567890abcd 이다.
## Acceptance
- A1: WP1 확인
NOT_RUN 표기.
[ANTI-STOP] 계속.
"""

BIG_BRIEF = ("# 지시서\n### WP1 일\n" + "설명 줄.\n" * 700 +
             "## Acceptance\n- A1: WP1 확인\nNOT_RUN 표기.\n[ANTI-STOP] 계속.\n")

MANY_PACKAGES = """# 지시서
### WP1 a
### WP2 b
### WP3 c
### WP4 d
### WP5 e
### WP6 f
## Acceptance
- A1: WP1 WP2 WP3 WP4 WP5 WP6 전부 확인
NOT_RUN 표기.
[ANTI-STOP] 계속.
"""

INLINE_RULES = """# 지시서
### WP1 일
## 2. 공통 규칙
- 파일 수정 전 lease를 잡고 끝나면 해제.
- git push / pull / add -A / commit / reset --hard 금지.
- .secrets/.env 열람 금지, 비밀·토큰 출력 금지.
- Gradle 실행 금지.
- 실제 외부 호출 0회, 유료 호출 금지.
## Acceptance
- A1: WP1 확인
NOT_RUN 표기.
[ANTI-STOP] 계속.
"""

NO_NOT_RUN = """# 지시서
### WP1 일
## Acceptance
- A1: WP1 확인
[ANTI-STOP] 계속.
"""


class BriefLintTest(unittest.TestCase):
    def test_good_brief_passes(self):
        result = brief_lint.lint_text(GOOD_BRIEF, name="PASTE_DEVIN_test.txt")
        self.assertEqual(result["verdict"], "PASS", result["findings"])

    def test_missing_anti_stop_fails(self):
        result = brief_lint.lint_text(NO_ANTI_STOP, name="b.txt")
        self.assertEqual(result["verdict"], "FAIL")
        self.assertIn("anti-stop-missing", _ids(result))

    def test_missing_acceptance_fails(self):
        result = brief_lint.lint_text(NO_ACCEPTANCE, name="b.txt")
        self.assertEqual(result["verdict"], "FAIL")
        self.assertIn("acceptance-missing", _ids(result))

    def test_uncovered_package_fails(self):
        result = brief_lint.lint_text(UNCOVERED_PACKAGE, name="b.txt")
        self.assertEqual(result["verdict"], "FAIL")
        self.assertIn("package-without-acceptance", _ids(result))

    def test_devin_without_skill_line_fails(self):
        result = brief_lint.lint_text(DEVIN_NO_SKILL, name="PASTE_DEVIN_x.txt")
        self.assertEqual(result["verdict"], "FAIL")
        self.assertIn("devin-skill-line-missing", _ids(result))

    def test_secret_like_string_fails_without_value(self):
        result = brief_lint.lint_text(WITH_SECRET, name="b.txt")
        self.assertEqual(result["verdict"], "FAIL")
        self.assertIn("secret-like-string", _ids(result))
        self.assertNotIn("sk-abcdef", str(result))

    def test_oversize_warns(self):
        result = brief_lint.lint_text(BIG_BRIEF, name="b.txt")
        self.assertIn("size-over-8kb", _ids(result))

    def test_many_packages_warns(self):
        result = brief_lint.lint_text(MANY_PACKAGES, name="b.txt")
        self.assertIn("package-count", _ids(result))

    def test_inline_common_rules_warns(self):
        result = brief_lint.lint_text(INLINE_RULES, name="b.txt")
        self.assertIn("common-rules-inline", _ids(result))

    def test_missing_not_run_warns(self):
        result = brief_lint.lint_text(NO_NOT_RUN, name="b.txt")
        self.assertIn("not-run-missing", _ids(result))


if __name__ == "__main__":
    unittest.main(verbosity=2)
