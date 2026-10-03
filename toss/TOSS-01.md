# TOSS-01 — Jev /v1/evaluate ZDR 기본 OFF + plan-gate 분류 (product seam, Codex 소유)

작성: devin / jev-no-zdr-standing-0929-7a482ae8 / 2026-09-29
통합 근거: DEMO1-DEVIN-JEV-NO-ZDR-STANDING-20260929
상태: IMPLEMENTED-BY-CODEX-SESSION (jev1b-source-0929 / jev1b-tests-0929) — 검증 결과는 아래 §검증.
선행 핸드오프: `data/agent-handoff/devin-sub-runtime-trace-r2-0929-888ffe6b/toss/TOSS-01.md` (OPEN) — 이 문서가 동일 스코프의 정식 지시/통합본이다.

## 배경 (사실 — 이 체크아웃에서 확인)

- 2026-09-29 10:28 UTC 라이브 `/v1/evaluate` = HTTP 403 `permission_denied`:
  "Zero Data Retention (ZDR) is only available for Pro and Enterprise plans. Current plan: hobby"
  → `data/agent-handoff/jev-smoke/jev-smoke-20260929-102827.json`. ZDR 요청이 hobby 플랜에서 plan-gate로 거절됨을 실증.
- ZDR 제거 후 1회 라이브 = HTTP 200 PASS (attempts=1, 836ms, 463in/64out tok, cost USD 0.000019446)
  → `jev-smoke-20260929-105339.json`. 추가 라이브 호출 없음(spend-guard).
- 규칙 SSOT: `docs/API_ROUTING_SPEC.md` "Vercel AI Gateway — Jev" (ZDR 기본 OFF; Pro+ 플랜 + 명시 설정시에만).
- 재발 방지 가드: `scripts/zdr_guard.py` (stdlib, Java-free) — 하드코딩된 literal-true ZDR을 탐지해 FAIL.

## 요청 작업 (product 소스 = Codex 소유; Devin 미수정)

1. `main/java/com/example/lms/assist/JevGatewayClient.java`
   - `providerOptions.gateway`에서 하드코딩된 `"zeroDataRetention",true` 제거.
   - 설정값 `demo.jev.zero-data-retention` (env `DEMO_JEV_ZDR`) 기본 `false`로 게이트.
   - 필드는 설정이 true일 때만 요청 본문에 포함.
   - 현재 상태: 구현됨 — `zeroDataRetention` 생성자 플래그(기본 false), `if(zeroDataRetention) gatewayOptions.put("zeroDataRetention",true)` (line ~62), 바인딩 `JevDecisionAdvisor.java:51` `env.getProperty("demo.jev.zero-data-retention",Boolean.class,false)`.
2. `main/resources/application-meta-display.yml` (~line 141-152)
   - `zero-data-retention: ${DEMO_JEV_ZDR:false}` + 주석 "Pro/Enterprise only; enabling this on Hobby can return HTTP 403" — 구현됨.
   - `demo.jev.mode/free-only/allow-paid`는 유지(변경 금지): `off`/`true`/`false` 그대로.
3. `src/test/java/com/example/lms/assist/JevGatewayClientTest.java`
   - 기본 요청 본문이 `zeroDataRetention`을 포함하지 않음을 단언 — 구현됨(`:45` assertFalse, `:53` opt-in assertTrue).
   - 분류: 401→`key_invalid_or_expired`, 403(+plan/ZDR 문구)→`plan_gate`, 기타 403→`forbidden`, 429→`rate_limited` — `JevGatewayClient.java:79-81,110-121` planGate403.
4. 에러 분류 스모크 쪽(Devin 소유, 이미 반영): `scripts/jev_gateway_smoke.mjs` — 401=`auth_invalid`, 403+plan=`plan_gate`, 기타 403=`permission_denied`, 429=`rate_limited`, 5xx=`upstream_error`, 출력 `zdr:"off"`. stub 12/12 PASS.

## 검증 (Codex 실행/재실행)

- `./gradlew.bat test --tests com.example.lms.assist.JevGatewayClientTest` (Devin 측에서도 1회 실행 — 결과는 PROJECT_STATUS/저널 참조)
- `python -B scripts/test_jev_gateway_smoke.py` (loopback stub 12/12, 무과금)
- 라이브 재호출 금지 — 기존 증거 사용. 필요 시 `python -B scripts/jev_api_smoke.py --live` 단 1회.
- `python -B scripts/zdr_guard.py` → PASS(hits=0) 유지할 것.

## 메모

- `demo.jev.mode=off` + `allow-paid=false`/`free-only=true`는 그대로. 라이브 200이어도 앱은 `budget_skip` 가능 — 켤지는 사용자 결정(ASK_ONCE 항목).
- mock/stub 결과를 라이브 증거로 치지 않는다. `.secrets` 값 출력 금지(이름/len/sha8만).
- 참고: `jev1b-*` lease가 product 파일을 잡고 있을 수 있음 — 충돌 시 lease status 확인.
