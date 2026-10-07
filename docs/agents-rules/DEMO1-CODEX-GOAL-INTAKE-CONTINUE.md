<!-- moved-from: AGENTS.md L78-L83 sha256=2025a6feb16ba882bafd88ebe868aa852a95aba5f31bb092f02138f2bcaaf5f0 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-CODEX-GOAL-INTAKE-CONTINUE -->
## Codex goal intake ≠ Done
- `$demo1-codex-goal-intake-continue` (`.agents/skills/demo1-codex-goal-intake-continue/SKILL.md`): goal-objective/첨부 PASTE 읽기는 intake. Done은 구현·검증 증거만; 등록 목표 제목은 구현 결과이지 "파일 읽기"가 아니다.
- 첨부 전체 읽기·분류는 `$demo1-attachment-coverage` (`docs/agents-rules/DEMO1-ATTACHMENT-COVERAGE.md`): manifest→전부 열기→verdict 표, 구현은 지시서 범위만.
- Completion claims that only assert reading goal-objective (`목표 파일 읽기 완료`, `read the goal … done`) are rejected by `demo1_goal_switch_barrier.py reject-complete` (exit 5).
- [P7 방지: Safe Goal Registration Protocol] — `create_goal` 무조건 호출 금지:
  1) 새 목표 등록 전 반드시 `get_goal`로 현재 스레드의 활성/미완료 목표를 확인한다.
  2) 기존 미완료 목표가 있으면 `update_goal`로 목표 내용을 덮어쓰거나, 이전 목표를 `status="completed"` 처리 후 `create_goal`을 호출한다.
  3) `cannot create a new goal … unfinished goal`(P7) 에러 발생 시 `create_goal` 재시도 금지 — 즉시 `update_goal`로 전환한다.
  사전 판정 CLI: `python -B scripts/demo1_goal_switch_barrier.py check-goal` → `action`=`UPDATE_EXISTING`|`CREATE_NEW` + 회복 스니펫.
<!-- END DEMO1-CODEX-GOAL-INTAKE-CONTINUE -->
