#!/usr/bin/env python3
"""deadline_scope_guard.py - guard-deadline-scope 정량 판정 CLI (읽기 전용).

demo-1 작업의 마감·범위 판단을 문서 규칙
(docs/agents-rules/DEMO1-DEADLINE-SCOPE-JUDGMENT.md)에서 실행 가능한
판정으로 내린다. 출력 판정은 권고이며 삭제·취소·기능 제거·규칙 우회의
권한이 아니다. 네트워크 0, 쓰기 0.

사용:
  python -B scripts/deadline_scope_guard.py --task-type fix \
      --target main/java/com/example/lms/api/Foo.java --est-seconds 600
  python -B scripts/deadline_scope_guard.py --task-type ai-integration --json

판정 (첫 매칭 규칙이 결정):
  1. task-type P0                    -> adopt-minimal  (P0·보안·정확성 결함 우선)
  2. 대상·추정·증거 전무             -> insufficient-evidence
  3. ai-integration, 잔여 <= 14일    -> defer          (외부 AI 3대 기준 미충족)
  4. 계획 예산 > 잔여 시간           -> refactor면 reject-current-design,
                                      아니면 reduce-scope
  5. 인증·공통 코드 침범(fix/feature)-> reduce-scope   (짧은 요청도 위험 큼)
  6. 기본                            -> adopt-minimal

계획 예산 = --est-seconds * 2.0 + (--has-browser-verify 시 120s 버퍼).
잔여 시간 = (deadline - now).days * 86400. 마감에 시각이 없으면 임의 시각을
만들지 않는다(문서 규칙). exit 0 = 판정 완료, 2 = 인자·입력 오류.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import date, datetime

SCHEMA = "awx.deadline-scope-guard.v1"
DEFAULT_DEADLINE = "2026-10-16"  # docs/agents-rules/DEMO1-DEADLINE-SCOPE-JUDGMENT.md
VERDICTS = (
    "adopt-minimal",
    "reduce-scope",
    "defer",
    "reject-current-design",
    "insufficient-evidence",
)
PLAN_FACTOR = 2.0          # 추정의 약 2배 = 초기 계획 여유
BROWSER_BUFFER_S = 120     # 브라우저/E2E 검증 버퍼
AI_DEFER_DAYS = 14         # 신규 외부 AI 도입 보류 마감 임계
SHORT_TASK_S = 3600        # 짧은 요청 상한
RISK_RE = re.compile(r"(?i)(auth|security|session|token|common)")
TASK_TYPES = ("P0", "fix", "feature", "refactor", "ai-integration")

# 신규 외부 AI/오케스트레이터(Jev 등) 도입 거절 3대 기준
EXTERNAL_AI_GATE = [
    "api-docs-missing: 공급자 공식 API 문서 부재",
    "circular-dependency-risk: 순환 의존 위험",
    "no-local-fallback-10ms: 10ms 로컬 폴백 불가",
]


def _parse_date(raw, flag):
    try:
        return datetime.strptime(raw, "%Y-%m-%d").date()
    except (ValueError, TypeError):
        raise SystemExit(
            "deadline-scope-guard: %s must be YYYY-MM-DD (got %r)" % (flag, raw))


def judge(task_type, targets, est_seconds, has_browser_verify,
          has_e2e_evidence, deadline, today):
    """규칙 체인 -> 판정 dict. 첫 매칭 규칙이 verdict를 결정한다."""
    targets = [t for t in targets if t]
    risky = sorted(t for t in targets if RISK_RE.search(t))
    days_remaining = (deadline - today).days
    remaining_seconds = days_remaining * 86400
    browser_buffer = BROWSER_BUFFER_S if has_browser_verify else 0
    planned_budget = (est_seconds * PLAN_FACTOR + browser_buffer
                      if est_seconds is not None else None)
    over_budget = (planned_budget is not None
                   and planned_budget > remaining_seconds)

    res = {
        "schemaVersion": SCHEMA,
        "verdict": None,
        "deadline": deadline.isoformat(),
        "now": today.isoformat(),
        "daysRemaining": days_remaining,
        "taskType": task_type,
        "targets": targets,
        "riskyTargets": risky,
        "estSeconds": est_seconds,
        "planFactor": PLAN_FACTOR,
        "browserBufferSeconds": browser_buffer,
        "plannedBudgetSeconds": planned_budget,
        "remainingSeconds": remaining_seconds,
        "evidence": {
            "hasBrowserVerify": bool(has_browser_verify),
            "hasE2eEvidence": bool(has_e2e_evidence),
        },
        "reasons": [],
        "nextAction": "",
    }

    def done(verdict, reasons, next_action):
        res["verdict"] = verdict
        res["reasons"] = reasons
        res["nextAction"] = next_action
        return res

    # 1. P0·보안·정확성 결함은 마감을 이유로 미루지 않는다.
    if task_type == "P0":
        return done("adopt-minimal",
                    ["P0·보안·정확성 결함: 마감 압박과 무관하게 최소 수정과 "
                     "검증을 우선한다"],
                    "필요한 최소 diff와 관련 검증부터 진행")

    # 2. 판정 근거 전무
    if not targets and est_seconds is None \
            and not (has_browser_verify or has_e2e_evidence):
        return done("insufficient-evidence",
                    ["변경 대상·추정·재현 증거가 모두 없어 범위 판정 불가"],
                    "재현 증거 또는 대상 파일 목록을 먼저 확보")

    # 3. 신규 외부 AI/오케스트레이터 도입의 마감 압박 보류
    if task_type == "ai-integration" and days_remaining <= AI_DEFER_DAYS:
        res["externalAiGate"] = list(EXTERNAL_AI_GATE)
        return done("defer",
                    ["신규 외부 AI/오케스트레이터 도입: 마감 잔여 %d일 "
                     "(<=%d일) — 통합·검증·복구 비용 불확실" % (
                         days_remaining, AI_DEFER_DAYS)] + EXTERNAL_AI_GATE,
                    "기존 대안 유지 + 재검토 조건(3대 기준 충족 증거) 제시")

    # 4. 계획 예산이 잔여 마감을 초과
    if over_budget:
        if task_type == "refactor":
            return done("reject-current-design",
                        ["계획 예산 %ds가 잔여 %ds 초과 — 현재 재설계 "
                         "설계로는 마감 내 완료 불가" % (
                             planned_budget, remaining_seconds)],
                        "더 단순한 기존 대안으로 목표 재구성")
        return done("reduce-scope",
                    ["계획 예산 %ds가 잔여 %ds 초과 — 범위 축소 필요" % (
                        planned_budget, remaining_seconds)],
                    "P0·검증·복구 시간을 cap 안에서 먼저 확보하고 범위 축소")

    # 5. 짧은 요청의 인증·공통 코드 침범
    if risky and task_type in ("fix", "feature") \
            and (est_seconds is None or est_seconds <= SHORT_TASK_S):
        return done("reduce-scope",
                    ["짧은 요청이 인증/공통 코드로 번짐: " + ", ".join(risky)],
                    "위험 경로를 분리 평가하고 허용된 최소 경로만 선택")

    # 6. 기본
    return done("adopt-minimal",
                ["최소 변경으로 목표 달성 가능"],
                "허용 범위에서 source RED -> 최소 diff -> GREEN 순서 유지")


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(
        description="guard-deadline-scope 정량 판정 CLI (읽기 전용, 권고)")
    ap.add_argument("--deadline", default=DEFAULT_DEADLINE,
                    help="YYYY-MM-DD (기본 %s, 문서 컨텍스트)" % DEFAULT_DEADLINE)
    ap.add_argument("--now", default=None,
                    help="YYYY-MM-DD 기준일 (기본 오늘, 로컬 날짜)")
    ap.add_argument("--target", action="append", default=[],
                    help="변경 대상 파일 경로 (반복 가능)")
    ap.add_argument("--task-type", default="fix", choices=TASK_TYPES,
                    help="작업 유형 (기본 fix)")
    ap.add_argument("--est-seconds", type=int, default=None,
                    help="추정 작업 시간(초); 미지정 시 예산 판정 생략")
    ap.add_argument("--has-browser-verify", action="store_true",
                    help="브라우저/E2E 검증 필요 시 120s 버퍼 추가")
    ap.add_argument("--has-e2e-evidence", action="store_true",
                    help="E2E/재현 증거 보유")
    ap.add_argument("--json", action="store_true", help="JSON 출력")
    args = ap.parse_args(argv)

    deadline = _parse_date(args.deadline, "--deadline")
    today = (_parse_date(args.now, "--now") if args.now
             else date.today())

    res = judge(args.task_type, args.target, args.est_seconds,
                args.has_browser_verify, args.has_e2e_evidence,
                deadline, today)

    if args.json:
        print(json.dumps(res, ensure_ascii=True))
    else:
        print("verdict: %s" % res["verdict"])
        print("deadline: %s (daysRemaining=%d)" % (
            res["deadline"], res["daysRemaining"]))
        if res["plannedBudgetSeconds"] is not None:
            print("plannedBudgetSeconds: %d (est=%ds x%s + browser=%ds)" % (
                res["plannedBudgetSeconds"], res["estSeconds"],
                res["planFactor"], res["browserBufferSeconds"]))
        else:
            print("plannedBudgetSeconds: n/a (est 미지정)")
        print("remainingSeconds: %d" % res["remainingSeconds"])
        if res["riskyTargets"]:
            print("riskyTargets: %s" % ", ".join(res["riskyTargets"]))
        for reason in res["reasons"]:
            print("reason: %s" % reason)
        print("nextAction: %s" % res["nextAction"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
