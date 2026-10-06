<!-- BEGIN DEMO1-JEV-RERANK-BOUNDARY -->
## Jev (Vercel AI Gateway) Rerank Boundary

- Jev는 검색 후보 선별(search-need/complexity 판정) **보조**에 한정한다 — 본문
  생성기가 아니다. 상세 계약: `docs/design/UNHOOKED_SEAMS_CONTRACT_SPEC.md` §S4.
  프로브: `python -B scripts/probe_unhooked_seams_guard.py --dry-run`.
- J1 `JevGatewayClient`는 `POST /v1/evaluate`만 호출한다("Evaluation only —
  never requests text generation"). 생성 엔드포인트·텍스트 완성 경로 추가 금지.
- J2 `JevRetrievalGateHandler`는 데코레이터다 — 비활성/스코프 없음이면 항상
  `delegate.handle(query,accumulator)` 패스스루. Jev 관측 실패가 후보 수집을
  0으로 만들지 않는다.
- J3 힌트는 좁히기만: permission boolean을 `true`로 승격 금지
  ("Permission booleans never become true"). `depth` 조정은 기존
  budget/provider 힌트를 보존한다.
- J4 실패는 fail-soft로 baseline 유지(`safeReason` 허용 목록에 수렴) — Jev
  장애·타임아웃으로 답변 본문을 보류(HOLD)하지 않는다. 임의 하드 타임아웃을
  새로 박지 않고 `TimeBudget` 데드라인을 쓴다.
- J5 부모 focus/cue 요청에 `main` 권한을 새로 발급하지 않는다
  (`JevDecisionScope.capture()!=null` → 패스스루).
- J6 자격증명은 `req.credentialEnv()`로 프로세스 로컬 해석, 리다이렉트 추적
  금지(`followRedirects NEVER`), 로그 출력 금지 — `JevGatewayClient` 계약 유지.
<!-- END DEMO1-JEV-RERANK-BOUNDARY -->
