# Clean 붙여넣기 — 바이브/에이전트 로그인·권한 가드레일 완화

Project Root: C:\AbandonWare\demo-1\demo-1\src
SSOT: agent-prompts/clean-vibe-agent-auth-relax-20260927/CLEAN_KICKOFF.md
교차: clean-vibe-low-admin-guardrail-20260927 (60 룰 있으면 확장)

의도: 바이브에서 안 쓰는 로그인/admin/토큰/logout-block/OS admin elevation 요구는 에이전트 규칙에서 N/A·완화.
남길 제약: secrets 출력, push/add -A, proto-open 배포, CSRF-off, foreign lease, 유료 API 무단.

할 일:
1) AGENTS BEGIN DEMO1-VIBE-AGENT-AUTH-RELAX 블록
2) .cline/60 확장 또는 61-demo1-vibe-agent-auth-relax.md Always On
3) hard-constraints/bridge의 로그인·실행권한 오도 문장 완화
4) proto-open=false / SecurityConfig harden / Admin 삭제 금지

검증: AGENTS·cline 6x·yml proto-open true. 규칙-only면 테스트 NOT_RUN.
끝나면 CLEAN_VIBE_AGENT_AUTH_RELAX: DONE|PARTIAL 보고.