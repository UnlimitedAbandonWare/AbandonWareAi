# Vibe Self-Ask judge (thin pointer)

SSOT: `.agents/skills/demo1-vibe-selfask-judge-auto/SKILL.md`
(`$demo1-vibe-selfask-judge-auto`). 이 파일은 포인터일 뿐 — 루프 본문을 복제하지
않는다. windsurf/devin 이중 미러 금지; `AGENTS.md` `DEMO1-VIBE-SELFASK-JUDGE-AUTO`
가 전 에이전트 공통 포인터다.

사용자 승인 퀴즈(1/2/3) 전 필수: POSITIVE → NEGATIVE → COUNTEREXAMPLE →
NEUTRAL JUDGE → `AUTO` / `ASK_ONCE`(질문 1개) / `HOLD` 중 하나만.
판정 후 `SELFASK_JUDGE <verdict> | reason | paths` journal 1행.
hard constraint(lease·secret·git remote·flag) 약화 금지.

VIBE_OPEN(`configs/vibe-open.yaml` enabled)이면 보안 검증·접근 인증 질문은
퀴즈 없이 "개방 유지 + DEFERRED_SECURITY" 자동 처리 — `docs/security/VIBE_OPEN.md`.
