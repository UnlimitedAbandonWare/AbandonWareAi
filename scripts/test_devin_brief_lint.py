#!/usr/bin/env python3
"""Synthetic fixtures for devin_brief_lint. Stdlib only."""
from __future__ import annotations

import sys
import tempfile
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

    def test_lease_hold_without_wait_warns(self):
        text = GOOD + "\nHOLD\n- live lease 충돌 시 HOLD로 종료.\n"
        result = lint.lint_text(text)
        self.assertIn("LEASE_HOLD_WITHOUT_WAIT", result["warnings"])
        self.assertEqual("GOOD", result["fit"])  # WARN은 차단이 아니다

    def test_lease_hold_with_wait_no_warn(self):
        text = GOOD + ("\nHOLD\n- live lease 충돌 시 lease-wait "
                       "--max-min auto 실행 후 재개.\n")
        result = lint.lint_text(text)
        self.assertNotIn("LEASE_HOLD_WITHOUT_WAIT",
                         result.get("warnings") or [])

    def test_phrase_with_file_list_is_not_handoff(self):
        text = GOOD.replace("한 줄 목표", "한 줄 목표\n전체를 다 고쳐 달라는 말은 쓰지 않습니다.", 1)
        result = lint.lint_text(text)
        self.assertEqual(result["fit"], "GOOD")

    # --- add-only: ADMIN_CHECK_UNDER_VIBE_OPEN (devin-admin-login-no-ask) ---

    def _enabled_vibe_cfg(self):
        tmp = tempfile.NamedTemporaryFile(
            mode="w", suffix=".yaml", delete=False, encoding="utf-8")
        tmp.write("enabled: true\n")
        tmp.close()
        return Path(tmp.name)

    def _disabled_vibe_cfg(self):
        tmp = tempfile.NamedTemporaryFile(
            mode="w", suffix=".yaml", delete=False, encoding="utf-8")
        tmp.write("enabled: false\n")
        tmp.close()
        return Path(tmp.name)

    def test_admin_check_in_acceptance_warns_under_vibe_open(self):
        cfg = self._enabled_vibe_cfg()
        try:
            text = GOOD.replace(
                "A1 PASS 조건. 안 돌린 것은 NOT_RUN.",
                "A1 관리자 로그인 성공과 로그아웃 후 차단을 실제 계정으로 검증.\n"
                "A2 PASS 조건. 안 돌린 것은 NOT_RUN.")
            result = lint.lint_text(text, vibe_open_path=cfg)
        finally:
            cfg.unlink(missing_ok=True)
        self.assertIn("ADMIN_CHECK_UNDER_VIBE_OPEN", result["warnings"])
        self.assertTrue(any("DEFERRED_SECURITY" in s for s in result["suggestions"]))

    def test_admin_check_outside_acceptance_no_warn(self):
        cfg = self._enabled_vibe_cfg()
        try:
            text = GOOD + ("\n참고\n- 상용구의 admin 로그인·로그아웃 차단 줄은 "
                           "관찰용 메모다 (Acceptance 아님).\n")
            result = lint.lint_text(text, vibe_open_path=cfg)
        finally:
            cfg.unlink(missing_ok=True)
        self.assertNotIn("ADMIN_CHECK_UNDER_VIBE_OPEN",
                         result.get("warnings") or [])

    def test_admin_check_no_warn_when_vibe_disabled(self):
        cfg = self._disabled_vibe_cfg()
        try:
            text = GOOD.replace(
                "A1 PASS 조건. 안 돌린 것은 NOT_RUN.",
                "A1 관리자 로그인 성공과 로그아웃 후 차단을 실제 계정으로 검증.\n"
                "A2 PASS 조건. 안 돌린 것은 NOT_RUN.")
            result = lint.lint_text(text, vibe_open_path=cfg)
        finally:
            cfg.unlink(missing_ok=True)
        self.assertNotIn("ADMIN_CHECK_UNDER_VIBE_OPEN",
                         result.get("warnings") or [])

    def test_vibe_open_boilerplate_rule_consistent_across_docs(self):
        # W1 일치 검사: 두 문서의 VIBE-OPEN-BOILERPLATE-RULE 블록 문장 동일.
        root = Path(__file__).resolve().parent.parent
        docs = (root / "docs/security/VIBE_OPEN.md",
                root / ".agents/skills/demo1-codex-plugin-roles/SKILL.md")
        blocks = []
        for doc in docs:
            text = doc.read_text(encoding="utf-8")
            start = text.find("<!-- VIBE-OPEN-BOILERPLATE-RULE v1 -->")
            end = text.find("<!-- /VIBE-OPEN-BOILERPLATE-RULE v1 -->")
            self.assertGreater(start, -1, doc.name)
            self.assertGreater(end, start, doc.name)
            block = " ".join(text[start:end].split())
            blocks.append(block)
        self.assertEqual(blocks[0], blocks[1])
        self.assertIn("TEMPLATE_BOILERPLATE", blocks[0])
        self.assertIn("DEFERRED_SECURITY", blocks[0])
        self.assertIn("다시 묻지 않는다", blocks[0])


if __name__ == "__main__":
    unittest.main()
