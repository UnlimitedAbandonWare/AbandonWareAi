# Devin 붙여넣기 — 바이브용 admin: 없애기 vs 열어두기

Project Root: C:\AbandonWare\demo-1\demo-1\src
역할: 감안·권고. harden/대량삭제는 기본 금지.

배경: AGENTS DEMO1-PROTOTYPE-AUTH-LIGHT = PROTO_OPEN. AdminTokenGuard가 ROLE_ADMIN을 누구나에게 줌. 직전 Codex도 proto-open 유지 → 로그아웃 후 admin 200은 정책이지 무조건 버그 아님. 사용자는 바이브 때 admin이 차라리 없거나 그냥 자유 접근이 낫다고 봄.

비교 Opt A 열어두기(현행) / B UI만 숨김 / C 라우트만 비활성(코드 삭제 금지) / D harden=바이브 기각 / E 하이브리드 A+B.

산출: agent-prompts/devin-vibe-admin-surface-20260927/RECOMMENDATION.md + THE ONE. 권고 시드=E(proto-open 유지+admin 크롬 숨김+바이브에서 logout-block 성공조건 제거).

금지: proto-open 끄기, CSRF off, Admin Java 싹삭제, secrets/push, madasin에 logout-block PASS 복원.

SSOT: 같은 폴더 DEVIN_KICKOFF.md
끝나면 DEVIN_VIBE_ADMIN: DONE|PARTIAL 보고.