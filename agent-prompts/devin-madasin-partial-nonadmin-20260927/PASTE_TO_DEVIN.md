# Devin 붙여넣기 — madasin PARTIAL (admin 제외) 잔여 해결

Project Root: C:\AbandonWare\demo-1\demo-1\src
SSOT 읽기: C:\Users\nninn\Downloads\madasin_Codex_design_recovery_2026-09-26\
지시 SSOT: agent-prompts/devin-madasin-partial-nonadmin-20260927/DEVIN_KICKOFF.md

admin/logout-block/proto-open 변경 = 범위 밖 (건드리지 말 것).
F01–F04·집중 fence·bootJar는 된 상태 — 재구현 금지.

문제→할 일:
P0 전체 test 116 + chatUiTest 6 분류(REGRESSION/STALE/EMF/UNRELATED)→원인 하나→최소 패치→해당만 재검증
P1 metadata-only↔assistantMessageId 실관측, attach SSE 재연결 (Browser; admin 시나리오 없음)
P2 EMF 인프라와 제품 버그 분리; remote SHA 불명이면 CI 비근거

플러그인 제한: Superpowers·Browser(D3)·GitHub 보조·Exa 공식·AWX 실패로그·glm 반박·Vercel=Jev만.
금지: 통째 이식, 새 트레이스/RAG, secrets, push, budget_skip 오류화.

보고: DEVIN_MADASIN_NONADMIN DONE|PARTIAL. PASS면 새 기능 말고 정지.