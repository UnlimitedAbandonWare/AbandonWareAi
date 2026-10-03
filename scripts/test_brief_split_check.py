#!/usr/bin/env python3
"""test_brief_split_check.py — brief_split_check.py 테스트. python -B scripts/test_brief_split_check.py

재사용한 기존 스크립트: brief_split_check.py (check)
새로 추가한 것: 정상 분할 / 누락+중복 / 단순 언급은 중복 아님 케이스
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import brief_split_check  # noqa: E402

SOURCE = """# 원본 지시서
### WP1 첫째
### WP2 둘째
### WP3 셋째
## Acceptance
- A1: WP1 확인
- A2: WP2 확인
- A3: WP3 확인
[ANTI-STOP] 계속.
"""

CARD1 = """# 카드1
### WP1 첫째
### WP2 둘째
## Acceptance
- A1: WP1 확인
- A2: WP2 확인
[ANTI-STOP] 계속.
"""

CARD2 = """# 카드2
### WP3 셋째
## Acceptance
- A3: WP3 확인
[ANTI-STOP] 계속.
"""

CARD2_DUP = """# 카드2 중복
### WP2 둘째 복사
### WP3 셋째
## Acceptance
- A2: WP2 확인
- A3: WP3 확인
[ANTI-STOP] 계속.
"""

CARD3_MENTION = """# 카드3 — WP1 결과를 참조해서 WP3 진행
### WP3 셋째
## Acceptance
- A3: WP3 확인
[ANTI-STOP] 계속.
"""


class SplitCheckTest(unittest.TestCase):
    def _write(self, dirpath: Path, name: str, text: str) -> Path:
        path = dirpath / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_clean_split_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            src = self._write(d, "src.txt", SOURCE)
            result = brief_split_check.check(
                src, [self._write(d, "c1.txt", CARD1), self._write(d, "c2.txt", CARD2)],
                ("WP",), ("A",))
            self.assertTrue(result["pass"], result)
            self.assertEqual(result["packages"]["missing"], [])
            self.assertEqual(result["acceptance"]["missing"], [])

    def test_missing_and_duplicate_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            src = self._write(d, "src.txt", SOURCE)
            # 카드1: WP1+WP2 정의 + A2만 (A1 누락) / 카드2: WP2+WP3 + A2+A3 → WP2·A2 중복 + A1 누락
            bad1 = CARD1.replace("- A1: WP1 확인\n", "")
            result = brief_split_check.check(
                src, [self._write(d, "c1.txt", bad1), self._write(d, "c2.txt", CARD2_DUP)],
                ("WP",), ("A",))
            self.assertFalse(result["pass"])
            self.assertIn("WP2", result["packages"]["duplicates"])
            self.assertIn("A1", result["acceptance"]["missing"])

    def test_plain_mention_is_not_duplicate(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            src = self._write(d, "src.txt", SOURCE)
            result = brief_split_check.check(
                src, [self._write(d, "c1.txt", CARD1), self._write(d, "c3.txt", CARD3_MENTION)],
                ("WP",), ("A",))
            self.assertTrue(result["pass"], result)
            self.assertIn("WP1", result["mentionsInOtherCards"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
