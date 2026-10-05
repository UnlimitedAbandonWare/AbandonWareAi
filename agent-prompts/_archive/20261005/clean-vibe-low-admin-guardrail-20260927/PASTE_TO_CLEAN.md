# Clean 붙여넣기 — 바이브 admin/룰 가드레일 낮추기

Project Root: C:\AbandonWare\demo-1\demo-1\src
역할: AGENTS·Cline·(필요시) windsurf 규칙을 Prototype Light로 낮추기. Security harden/Admin 코드 삭제 금지.

시드 THE ONE (Devin RECOMMENDATION 없으면): PROTO_OPEN 유지 + 바이브 Done에서 admin 로그인/로그아웃차단 제거 + Cline Always-on 룰 1장. UI 숨김은 Devin이 화이트리스트 줄 때만.

할 일:
1) AGENTS DEMO1-PROTOTYPE-AUTH-LIGHT에 바이브 N/A 조항 보강
2) .cline/60-demo1-vibe-low-admin-guardrail.md (번호 충돌 시 조정) Always On
3) hard-constraints 등에서 admin 강제 문장 → proto-open 예외로 완화
4) proto-open=false / CSRF off / Admin Java 싹삭제 / logout-block PASS 테스트 만들기 금지

교차: agent-prompts/devin-vibe-admin-surface-20260927/
SSOT: agent-prompts/clean-vibe-low-admin-guardrail-20260927/CLEAN_KICKOFF.md
끝나면 CLEAN_VIBE_LOW_ADMIN: DONE|PARTIAL 보고.