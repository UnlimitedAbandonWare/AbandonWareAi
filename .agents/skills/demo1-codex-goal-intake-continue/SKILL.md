---
name: demo1-codex-goal-intake-continue
description: Use when Codex or demo-1 session starts from goal-objective.md / attached PASTE so reading is never Done
---

# Codex Goal Intake → Continue

`goal-objective.md`, 첨부 PASTE, Downloads 지시는 **읽기 SSOT**(입력 위치)다.
제품 루트는 항상 `<repo>`. 읽기 행위는 절대 완료
조건이 아니다.

## 규칙

1. **목표 제목은 구현 결과에서 뽑는다.** PASTE 본문의 `## 목표 한 줄` /
   `Mission` / `완료 정의`를 제목으로 등록한다. 파일명이나 "읽기"를 목표
   제목으로 쓰지 않는다 — 이미 그렇게 등록됐다면 mis-registration으로
   간주하고 본문의 Mission/WP 완료 정의로 재해석해 계속한다.
2. 순서: Read intake → (optional) `demo1_goal_switch_barrier.py check` →
   **implement smallest seam** → verify → only then
   `$demo1-goal-complete-stop`.
3. "완료까지 N초"인데 소스 diff 0이면 **미완료**. CONTINUE가 의무다.
   `reject-complete`가 `goal-read-done`/`read-goal-done`/
   `registered-goal-read-done`/`intake-only-done` 패턴을 exit 5로 거부한다.
4. Done 직전 최소 하나: (a) 제품/소스 diff, (b) 해당 WP 테스트 결과,
   (c) `BLOCKED` + 막힌 파일 + 이유. 읽기-only 보고서로
   `$demo1-goal-complete-stop` 호출 금지.

## Related

- `$demo1-goal-complete-stop` — Done 게이트 + barrier `reject-complete` 호출
- `scripts/goal_next_auto.ps1`, `scripts/goal_next.ps1`, `scripts/autograde_b_rail.py` — goal rail/auto-vibe 독자도 동일 규칙: stale goal-objective 읽기는 intake; DB/도구 최소 세트는 `docs/DB_AGENT_CHEATSHEET.md`의 goal-rail 분리 섹션 참조
- `$demo1-goal-switch-barrier` — instructional 판정 (exit 5)
- `$demo1-desktop-canonical-goal-intake` — 정확한 구현 지시의 intake 계약
- `docs/operations/codex-goal-footer-the-one.txt` — `[ANTI-STOP]` 꼬리
- `$demo1-codex-auto-decide` — 선택/승인 질문 유형별 기본 답 표(D1~D12); 표 매치는 AUTO + `AUTO_DECISION:` 기록

## 병렬 채팅 첫 수정 전 preflight (additive)

여러 채팅이 같은 목표를 받을 수 있으면 intake 다음, 첫 수정 전에
`python -B scripts/codex_parallel_preflight.py --root . --goal-key <key>
--scope <paths> --agent <name> --json` — `DUPLICATE_GOAL_LIVE`면
읽기 전용(VERIFIER)이고 진행이 아니다. 상세:
`.agents/skills/demo1-codex-parallel-lanes/SKILL.md`.
