---
name: demo1-goal-blocked-loop-breaker
description: Use when a demo-1 Codex goal hits the same blocker twice, is marked blocked, or the user keeps typing 계속 without progress.
---

# Goal Blocked Loop Breaker

코덱스 목표(/goal) 세션이 "같은 막힘 3턴 연속 → blocked"에 빠지는 것을 끊는다.
세 번째 감사는 가치가 없다. 두 번째 같은 막힘에서 이 스킬을 연다.

## 코덱스(실행자)가 할 일

1. 같은 막힘을 **두 번째** 확인했으면 세 번째 감사를 하지 말고 진단기를 돌린다.
   범위 해석 질문으로 2턴 연속 멈추면 그것도 같은 막힘(SCOPE_AMBIGUITY)이다.
   `python -B scripts/goal_block_triage.py --rollout <내 세션 jsonl>`
   (또는 `--ledger data/agent-handoff/<작업폴더>` — blocked-audit 파일이 이미 있으면 그것이 우선 근거)
2. 원인이 범위 밖이면 그 항목을 **HOLD-EXT**로 표시한다. 범위 밖 원인 = 다른 세션
   lease(LEASE_HELD), 측정 불가 증거(UNMEASURABLE_EVIDENCE), 지시서 모순
   (CONTRADICTORY_ACCEPTANCE), 도구 한도(TOOL_LIMIT), 권한 밖 경로, 예산 소진.
3. 보고 형태는 **"완료(범위 안 PASS n/n, 외부 HOLD m개 + 재개 조건)"** 이다.
   `blocked`는 범위 안 항목이 실제로 막혔을 때만 쓴다. lease가 풀렸거나 도구가
   이미 고쳐졌으면 triage가 RESUMABLE_NOW를 내니 그대로 이어간다.
4. 사용자 "계속"이 같은 막힘 위에 오면 재감사 대신 **재개 조건 1줄 + 붙여 넣을
   말 1줄**을 낸다(triage 출력의 paste 줄을 그대로 써도 된다).

## 지시서 작성자(Grok Bot·GPT Pro·사람)가 할 일

1. 넘기기 전에 반드시:
   `python -B scripts/acceptance_reachability_lint.py --live-leases <지시서>`
   **FAIL 0**이어야 전달한다. WARN은 재개 조건을 적고 진행 가능.
2. 다른 세션 lease에 걸린 경로, 측정 불가 항목은 Acceptance가 아니라 **HOLD
   섹션에 재개 조건과 함께** 둔다. HOLD 섹션 항목을 Acceptance에 PASS 조건으로
   다시 넣지 않는다.
3. "완료 = Acceptance 전부 PASS" 원칙은 유지한다. 대신 Acceptance에는 **도달
   가능한 항목만** 넣고, 도달 불가 항목은 HOLD-EXT로 옮긴다. "PARTIAL로 보고"를
   지시한 항목이 완료 조건에 남아 있으면 R3 FAIL — 그 항목을 HOLD-EXT로 이동.
4. 한 줄 보험: "범위 밖 HOLD 항목은 완료 조건에서 제외"를 지시서에 넣는다(R5).

## 분류 → 붙여 넣을 말 (triage가 자동 생성)

- RESUMABLE_NOW: 「막힘 원인 <X>가 풀렸어. 같은 감사 반복하지 말고 남은 항목부터 바로 이어서 해줘.」
- DONE_WITH_DEFERRED(VIBE_OPEN 상용구 보안 항목만 남음): 「남은 막힘이 상용구 admin 로그인·차단·보호 URL뿐이야 — DEFERRED_SECURITY로 기록하고 완료로 보고해줘(docs/security/VIBE_OPEN.md).」
- SCOPE_AMBIGUITY(보호·범위 해석이 모호해 멈춤): 「보호 범위 해석이 모호한 게 막힘 원인이야. `docs/agents-rules/DEMO1-AGENT-GUARD-COMMON.md`의 '보호 범위 해석' (1)~(5)를 적용해 바로 진행하고, 같은 질문으로 다시 감사하지 말아줘.」
- 범위 밖 원인: 「<항목>은 범위 밖(<원인>)이라 HOLD로 빼고, 범위 안 항목이 전부 PASS면 완료로 보고해줘.」
- 지시서 모순: 「<항목>은 지시서대로 PARTIAL이 정답이니 완료 조건에서 빼고 완료로 보고해줘.」

## 관련

- `../scoped-blocker-recovery/SKILL.md` — 반복 HOLD/no_progress·lease 생명주기.
- `../demo1-goal-complete-stop/SKILL.md` — 검증 끝난 목표에서 멈추는 규칙.
- 입력 자료: `scripts/codex_session_friction.py`, ledger의 `blocked-audit*.{json,md}`.
