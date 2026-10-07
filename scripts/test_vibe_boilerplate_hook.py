#!/usr/bin/env python3
"""VIBE_OPEN boilerplate injection tests for
.codex/hooks/project_capabilities_hook.ps1 (devin-vibe-admin-autodefer).

Contract (hooks.json additionalContextLimit=512):
- prompt containing the plugin-role boilerplate block (>=2 numbered plugin
  lines + admin/login/block language) while configs/vibe-open.yaml is
  enabled -> hookSpecificOutput.additionalContext gains exactly one
  VIBE_OPEN injection line, total <=512 chars
- non-boilerplate prompt -> output unchanged (no VIBE_OPEN line)
- any failure path (bad stdin, missing scripts/config) -> exit 0 anyway

Run: python -B scripts/test_vibe_boilerplate_hook.py
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HOOK = ROOT / ".codex" / "hooks" / "project_capabilities_hook.ps1"
PWSH = os.environ.get("HOOK_PWSH") or "powershell"
TIMEOUT = 90

# 2026-10-07 rollout goal-objective의 실제 상용구 블록 형태 (Markdown 이스케이프 해제본).
BOILERPLATE_PROMPT = """\
요약 경계 수정과 회귀 검증을 해줘.

1. Superpowers
- systematic-debugging과 verification-before-completion을 적용해.
2. Browser
- 실제 웹에서 최소한 다음을 새 세션으로 재현해.
 - 안녕? → 응답 본문이 HOLD 처리되는지
 - 일반 질문 → backend_unavailable 발생 여부
 - admin 로그인 → 실제 보호된 관리자 URL 접근
 - 잘못된 계정 → 차단
 - 로그아웃 후 → 다시 차단
- UI 문구만 보지 말고 HTTP 상태, 서버 reasonCode와 연결해.
3. GitHub
- 로컬 git status와 HEAD를 먼저 봐.
"""


def run_hook_file(path, stdin_text, cwd):
    return subprocess.run(
        [PWSH, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(path)],
        input=stdin_text.encode("utf-8"),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        cwd=str(cwd), timeout=TIMEOUT)


def run_hook(event):
    proc = run_hook_file(HOOK, json.dumps(event, ensure_ascii=False), ROOT)
    return proc.returncode, proc.stdout, proc.stderr


def parse_stdout(out: bytes):
    if not out or not out.strip():
        return "empty", None
    try:
        payload = json.loads(out.decode("utf-8"))
    except Exception:
        return "invalid", None
    return "object", payload


class VibeBoilerplateHookTest(unittest.TestCase):

    def test_boilerplate_prompt_injects_vibe_line(self):
        event = {"hook_event_name": "UserPromptSubmit",
                 "prompt": BOILERPLATE_PROMPT, "session_id": "t1"}
        code, out, err = run_hook(event)
        self.assertEqual(0, code, "exit=%s err=%r" % (code, err[:300]))
        kind, payload = parse_stdout(out)
        self.assertEqual("object", kind, "stdout=%r" % out[:300])
        hso = payload.get("hookSpecificOutput") or {}
        self.assertEqual("UserPromptSubmit", hso.get("hookEventName"))
        ctx = str(hso.get("additionalContext") or "")
        self.assertIn("VIBE_OPEN", ctx)
        self.assertIn("TEMPLATE_BOILERPLATE", ctx)
        self.assertIn("DEFERRED_SECURITY", ctx)
        self.assertLessEqual(len(ctx), 512)

    def test_non_boilerplate_prompt_no_injection(self):
        event = {"hook_event_name": "UserPromptSubmit",
                 "prompt": "hello — continue the summary fix",
                 "session_id": "t2"}
        code, out, err = run_hook(event)
        self.assertEqual(0, code, "exit=%s err=%r" % (code, err[:300]))
        kind, payload = parse_stdout(out)
        if kind == "object":
            ctx = str((payload.get("hookSpecificOutput") or {})
                      .get("additionalContext") or "")
            self.assertNotIn("VIBE_OPEN", ctx)
            self.assertNotIn("TEMPLATE_BOILERPLATE", ctx)

    def test_malformed_stdin_still_exit_0(self):
        proc = run_hook_file(HOOK, "{not json", ROOT)
        self.assertEqual(0, proc.returncode)
        kind, _ = parse_stdout(proc.stdout)
        self.assertIn(kind, ("empty", "object"))

    def test_missing_inputs_fail_soft(self):
        # temp tree에는 scripts/config가 없으므로 hook은 stderr만 남기고 exit 0.
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            hooks = td / ".codex" / "hooks"
            hooks.mkdir(parents=True)
            copied = hooks / "project_capabilities_hook.ps1"
            shutil.copy2(HOOK, copied)
            proc = run_hook_file(
                copied,
                json.dumps({"hook_event_name": "UserPromptSubmit",
                            "prompt": BOILERPLATE_PROMPT}, ensure_ascii=False),
                td)
            self.assertEqual(0, proc.returncode)
            kind, _ = parse_stdout(proc.stdout)
            self.assertIn(kind, ("empty", "object"))


if __name__ == "__main__":
    unittest.main()
