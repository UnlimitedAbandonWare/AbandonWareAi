<!-- moved-from: AGENTS.md L78-L83 sha256=2025a6feb16ba882bafd88ebe868aa852a95aba5f31bb092f02138f2bcaaf5f0 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-CODEX-GOAL-INTAKE-CONTINUE -->
## Codex goal intake ≠ Done
- `$demo1-codex-goal-intake-continue` (`.agents/skills/demo1-codex-goal-intake-continue/SKILL.md`): goal-objective/첨부 PASTE 읽기는 intake. Done은 구현·검증 증거만; 등록 목표 제목은 구현 결과이지 "파일 읽기"가 아니다.
- 첨부 전체 읽기·분류는 `$demo1-attachment-coverage` (`docs/agents-rules/DEMO1-ATTACHMENT-COVERAGE.md`): manifest→전부 열기→verdict 표, 구현은 지시서 범위만.
- Completion claims that only assert reading goal-objective (`목표 파일 읽기 완료`, `read the goal … done`) are rejected by `demo1_goal_switch_barrier.py reject-complete` (exit 5).
<!-- END DEMO1-CODEX-GOAL-INTAKE-CONTINUE -->
