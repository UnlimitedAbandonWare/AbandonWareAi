#!/usr/bin/env python3
"""test_deadline_scope_guard.py - deadline_scope_guard.py 단위 테스트.

실제 마감일 대신 --now 기준일을 고정해 결정적으로 판정을 검증한다.
네트워크 0, live 트리 쓰기 0 — judge()/main()만 호출한다.
"""
from __future__ import annotations

import importlib.util
import io
import json
import unittest
from contextlib import redirect_stdout
from datetime import date
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "deadline_scope_guard",
    str(Path(__file__).resolve().parent / "deadline_scope_guard.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

DEADLINE = date(2026, 10, 16)
NOW = date(2026, 10, 6)


def run(task_type="fix", targets=(), est=None, browser=False, e2e=False,
        deadline=DEADLINE, now=NOW):
    return mod.judge(task_type, list(targets), est, browser, e2e,
                     deadline, now)


def cli(*argv):
    buf = io.StringIO()
    with redirect_stdout(buf):
        code = mod.main(list(argv))
    return code, buf.getvalue()


class DeadlineScopeGuardTest(unittest.TestCase):

    # 1) P0 버그 수리 -> adopt-minimal
    def test_p0_adopt_minimal(self):
        res = run(task_type="P0",
                  targets=["main/java/com/example/lms/api/ChatApi.java"],
                  est=600)
        self.assertEqual(res["verdict"], "adopt-minimal")

    # 2) 짧은 요청이 인증/공통 코드 침범 -> reduce-scope
    def test_short_task_touching_auth_reduces_scope(self):
        res = run(task_type="fix",
                  targets=["main/java/com/example/lms/security/AuthFilter.java"],
                  est=600)
        self.assertEqual(res["verdict"], "reduce-scope")
        self.assertTrue(res["riskyTargets"])

    def test_short_task_touching_common_reduces_scope(self):
        res = run(task_type="feature",
                  targets=["scripts/common/shared_util.py"], est=900)
        self.assertEqual(res["verdict"], "reduce-scope")

    # 3) 신규 외부 AI 연동 + 마감 압박 -> defer
    def test_ai_integration_deferred_near_deadline(self):
        res = run(task_type="ai-integration",
                  targets=["scripts/jev_bridge.py"], est=7200,
                  now=date(2026, 10, 10))  # 잔여 6일 <= 14일
        self.assertEqual(res["verdict"], "defer")
        self.assertEqual(res.get("externalAiGate"), mod.EXTERNAL_AI_GATE)

    def test_ai_integration_not_deferred_when_far(self):
        res = run(task_type="ai-integration",
                  targets=["scripts/jev_bridge.py"], est=600,
                  now=date(2026, 9, 20))  # 잔여 26일 > 14일
        self.assertNotEqual(res["verdict"], "defer")

    # 4) 재현 증거·대상 부재 -> insufficient-evidence
    def test_insufficient_evidence(self):
        res = run(task_type="fix", targets=(), est=None)
        self.assertEqual(res["verdict"], "insufficient-evidence")

    # 5) 2배 예산 + 브라우저 버퍼 계산
    def test_budget_math(self):
        res = run(task_type="fix",
                  targets=["scripts/x.py"], est=300, browser=True)
        self.assertEqual(res["plannedBudgetSeconds"], 300 * 2 + 120)
        self.assertEqual(res["browserBufferSeconds"], 120)
        self.assertEqual(res["remainingSeconds"], 10 * 86400)

    def test_no_browser_flag_no_buffer(self):
        res = run(task_type="fix", targets=["scripts/x.py"], est=300)
        self.assertEqual(res["plannedBudgetSeconds"], 600)
        self.assertEqual(res["browserBufferSeconds"], 0)

    # 6) JSON 출력 규격
    def test_json_output_schema(self):
        code, out = cli("--task-type", "fix", "--target", "scripts/x.py",
                        "--est-seconds", "300", "--now", "2026-10-06",
                        "--deadline", "2026-10-16", "--json")
        self.assertEqual(code, 0)
        data = json.loads(out)
        self.assertEqual(data["schemaVersion"], "awx.deadline-scope-guard.v1")
        self.assertIn(data["verdict"], mod.VERDICTS)
        self.assertEqual(data["daysRemaining"], 10)

    # 추가: 계획 예산 초과 — refactor는 reject-current-design, 아니면 reduce-scope
    def test_over_budget_refactor_rejected(self):
        res = run(task_type="refactor", targets=["scripts/x.py"],
                  est=500000, now=date(2026, 10, 15))  # 잔여 1일
        self.assertEqual(res["verdict"], "reject-current-design")

    def test_over_budget_feature_reduces_scope(self):
        res = run(task_type="feature", targets=["scripts/x.py"],
                  est=500000, now=date(2026, 10, 15))
        self.assertEqual(res["verdict"], "reduce-scope")

    def test_help_exits_zero(self):
        with self.assertRaises(SystemExit) as ctx:
            with redirect_stdout(io.StringIO()):
                mod.main(["--help"])
        self.assertEqual(ctx.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
