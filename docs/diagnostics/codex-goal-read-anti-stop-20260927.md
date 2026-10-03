# codex-goal-read anti-stop — 2026-09-27

## 증상 (사실, 실측)
- Codex 세션이 `goal-objective.md`(WP 구현 PASTE)만 읽고 "이번에 등록된 목표인
  목표 파일 읽기는 완료"로 종료. 제품 WP0–WP4 미구현.
- 원인: barrier의 `reject-complete`는 `Read … before continuing` 류 지시문만
  막고, 등록 목표 제목 자체가 "파일 읽기"이면 통과시킴.

## before 증거 (이 checkout 실측)
- 명령: `python -B scripts/demo1_goal_switch_barrier.py reject-complete --text "지정한 goal-objective.md를 읽었습니다. 이번에 등록된 목표인 목표 파일 읽기는 완료했습니다."`
- 결과: exit 0 — `{"rejected": false, "reason": "not-instructional", "matched": []}` (2026-09-27 ~10:15 UTC)

## after (패치 후, 같은 명령)
- exit 5 — `matched = [goal-read-done, read-goal-done, registered-goal-read-done]`
- `acceptance: WP1 web.search flag default OFF; ToolRegistry matches manifest` → exit 0
- `python -B scripts/test_demo1_goal_switch_barrier.py` → 33/33 OK (신규 회귀 9건 포함)

## 변경 파일
- `scripts/demo1_goal_switch_barrier.py` — INSTRUCTIONAL에 goal-읽기/intake-only 패턴 4종 추가
- `scripts/test_demo1_goal_switch_barrier.py` — 회귀 테스트 9건 (KO/EN reject + 진짜 acceptance 통과)
- `.agents/skills/demo1-goal-complete-stop/SKILL.md` — "Goal intake ≠ acceptance" 섹션 + anti-pattern
- `.agents/skills/demo1-codex-goal-intake-continue/SKILL.md` — 신규 thin skill (읽기≠Done, 제목=구현 결과)
- `.agents/skills/demo1-desktop-canonical-goal-intake/SKILL.md` — 목표 제목=구현 결과 한 줄 (WP5)
- `AGENTS.md` — `DEMO1-CODEX-GOAL-INTAKE-CONTINUE` 포인터 블록
- `docs/operations/codex-goal-footer-the-one.txt` + `agent-prompts/_templates/CODEX_PASTE_ANTI_STOP_FOOTER.txt` — `[ANTI-STOP]` 꼬리
- `.agents/skills-intent-index.yaml` — `codex-goal-intake` 인텐트 등록

## 완료 정의 (이 티켓)
Codex가 goal-objective만 읽고 "목표 파일 읽기 완료"로 끝내면 barrier·skill이
instructional로 막는 근거가 repo SSOT에 존재한다. 제품 WP 구현 성공은 범위 밖.
