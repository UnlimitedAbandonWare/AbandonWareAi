# goal-objective "읽기 완료"를 작업 완료로 착각하는 anti-stop — barrier reject-complete가 차단
- card-id: goal-read-anti-stop-g92a
- kind: root-cause
- status: still-true (re-verified 2026-10-03, rejected=true)
- date: 2026-09-27 KST
- evidence: docs/diagnostics/codex-goal-read-anti-stop-20260927.md ; scripts/demo1_goal_switch_barrier.py
- reverify: `python -B scripts/demo1_goal_switch_barrier.py reject-complete --text "지정한 goal-objective.md를 읽었습니다. 이번에 등록된 목표인 목표 파일 읽기는 완료했습니다."`

## 근거
- 증상: Codex 세션이 goal-objective.md만 읽고 "목표 파일 읽기 완료"로 종료(제품 WP 미구현).
- 원인: barrier의 `reject-complete`가 `Read … before continuing` 류 지시문만 막고, 등록 목표 제목
  자체가 "파일 읽기"이면 통과시켰음.
- 패치 후(현재 checkout 실측 2026-10-03): 같은 명령 → `rejected=true`,
  `matched=[goal-read-done, read-goal-done, registered-goal-read-done]` (exit 5).
- 회귀: scripts/test_demo1_goal_switch_barrier.py 33/33 (작성 시점).
