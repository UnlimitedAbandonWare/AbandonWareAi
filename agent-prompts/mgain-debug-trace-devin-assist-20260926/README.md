# Devin ASSIST ONLY — Codex debug-trace restore support (NO source edits)

Role: Codex가 /chat 답변별 디버깅 트레이스 복원을 끝낼 수 있게
도구·스크립트·셋업·룰·지침·검증 하네스만 준비. **제품 Java/JS/CSS 소스 직접 수정 금지.**

Project Root (Codex 작업 대상, 네가 고치지 않음): C:\AbandonWare\demo-1\demo-1\src
SSOT: C:\Users\nninn\Downloads\MGAIN_DEBUG_TRACE_RESTORE_2026-09-26\
ZIP: C:\Users\nninn\Downloads\MGAIN_DEBUG_TRACE_RESTORE_2026-09-26.zip

See Grok Bot handoff for full Korean brief.

## Prepared artifacts (Devin assist, 2026-09-26)

- `codex-kickoff.md` — 경로 고정, HARD 완료 조건, 구현 순서, 막힘별 유도 문구, 재킥오프 문구
- `t01-t16-verification-checklist.md` — T01–T16 PASS/FAIL/NOT_RUN 기록표 + 라이브 앵커 맵
- `scripts/mgain_trace_smoke.py` — 읽기 전용 정적 스모크 (`python -B scripts/mgain_trace_smoke.py [--strict|--json|--anchors-only]`)
- `.agents/skills/mgain-debug-trace-restore/SKILL.md` — 라우터용 1줄 규칙 포인터

Note: Codex task `mgain-trace-restore-0926-adc8ae54` holds the product-source lease
(`mgain-trace-restore-0926.lock`, 8 targets incl. chat.js/ChatApiController/TraceHtmlBuilder).
Assist layer owns only the docs/script/skill paths above — 제품 소스 diff 0.
