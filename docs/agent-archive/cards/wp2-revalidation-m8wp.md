# mdasain WP2 재검증: 73 tests 0 fail, inventory env parsed 39/present 25, credential 조회가 route/spend를 만들지 않음
- card-id: wp2-revalidation-m8wp
- kind: measured-number
- status: still-true (역사 측정값, Codex WP0/1/3/4 반영 후 재실행분)
- date: 2026-09-27 KST
- evidence: docs/diagnostics/mdasain-wp2-closeout-20260927/WP2-revalidation.md
- reverify: `Get-Content docs\diagnostics\mdasain-wp2-closeout-20260927\WP2-revalidation.md`

## 근거
- verify-wp2-tests: JUnit 73 tests / 0 failures / 0 errors / 0 skipped
  (KeyResolverProviderKeyTest 13, DebugEventTracePromotionServiceTest 25, 등 8 클래스).
- 라이브 관측(18180): `api.credential.resolved` 이벤트 parsedCount=39, presentCount=25,
  absentCount=14 — env 이름/개수만, 값 없음.
- KeyResolver/boot 조회 10건 → `api.route.*`/`api.spend.*` 미생성(credential ≠ route 증명).
- WP2 소유 파일 sha256 4종이 이전 실행과 동일 — Codex diff 0.
