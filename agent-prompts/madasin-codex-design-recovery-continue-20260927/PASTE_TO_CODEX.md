# Codex 붙여넣기 — madasin recovery CONTINUE (PARTIAL 이어서)

Project Root: C:\AbandonWare\demo-1\demo-1\src
패키지 SSOT: C:\Users\nninn\Downloads\madasin_Codex_design_recovery_2026-09-26\
이번 SSOT: agent-prompts/madasin-codex-design-recovery-continue-20260927/CODEX_CONTINUE.md

직전 실행은 do09 PARTIAL. F01–F04·do05–do07·집중 fence·bootJar·Verify-RAG는 된 상태. 다시 do00부터 / F01부터 하지 말 것.

이어서 할 것:
1) C0 인수인계: git status/HEAD, 실패 목록 확보
2) C1 전체 test 116 분류→원인 하나씩→해당만 패치+재검증 (전부 기존실패 단정 금지)
3) C2 chatUiTest 6 동일
4) C3 evidence_needed 런타임(실관측 또는 명시 잔여): snapshot↔assistantMessageId, attach SSE, memory/attach
5) C4 proto-open 유지 — 로그아웃 후 차단은 불충족으로 보고 (정책 바꾸지 말 것)
6) C5 do09 재판정. PASS면 새 기능 말고 정지. PARTIAL이면 잔여만 남기고 정지.

플러그인: Superpowers(systematic-debugging)·Browser(미관측만)·GitHub(보조)·Exa(공식규격)·AWX(실패로그 분류)·Computer(로컬UI만)·glm_worker(반박검토)·Vercel(Jev만). 나머지 기본 미사용. 역할 제한 준수.

금지: 통째 이식, 새 트레이스/RAG 엔진, secrets, push/add -A, budget_skip 오류화, foreign staging 훼손.

보고: Abandon.txt 형식. “문서만 읽음”≠완료.