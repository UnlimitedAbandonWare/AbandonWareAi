# DEMO1-JEV-FACT-META-SSOT

Jev Choice(`typesafe-ai/jev`, Vercel AI Gateway `POST /v1/evaluate`)를
`FACT_META_CHECK`에 도입할 때의 단일 기준 문서. 2026-10-07 Devin Companion
Assist 산출물이며, 제품 소스 패치는 Codex 단독 소유다.

## 1. 허용 seam — 단 한 곳

- 대상: `FactVerifierService`의 meta-check 분기만 — `optionalMetaVerdict()`
  (`main/java/com/example/lms/service/FactVerifierService.java:294,448`)가
  `jevChoiceAdvisor.factMetaVerdict(...)`를 시도하고, 실패·비활성 시 기존
  `callChatModel(FACT_META_CHECK)` 경로로 폴백한다.
- `factMeta` 질문은 **항상 단독 배치**다: `JevChoiceAdvisor.validQuestions()`가
  FACT_META 포함 시 questions.size()==1을 강제한다
  (`main/java/com/example/lms/assist/JevChoiceAdvisor.java:170-178`).
- 다른 호출 지점 금지 — `.factMetaVerdict(` 수신자 호출은
  `FactVerifierService.java`에만 존재해야 한다. 감시:
  `python -B scripts/check_jev_nightmare_boundary.py` (B4).

## 2. NightmareBreaker 결합 금지 (순환 의존 방지)

- 회로 차단기는 로컬 카운트/CAS만으로 충분하다. `NightmareBreaker.java`에
  Jev·assist·`ai-gateway.vercel.sh`·`/v1/evaluate` 참조 추가 금지 (B1).
- 역방향도 금지: `main/java/com/example/lms/assist/**`가
  `NightmareBreaker`를 참조하면 안 된다 (B2).
- 레거시 엔드포인트 `typesafe/v1/systemone` 문자열 재등장 금지;
  `/v1/evaluate` 참조는 assist 패키지·`application-meta-display.yml`·테스트
  안에만 둔다 (B3).

## 3. 활성화 계층 — default-OFF 누적 게이트

`factMetaVerdict`가 실제 호출되려면 아래가 모두 참이어야 한다
(`JevChoiceAdvisor.factMetaVerdict()` :121-168). 하나라도 빠지면 조용히
baseline이다 — 이는 버그가 아니라 계약이다.

| 게이트 | 키 |
|---|---|
| choice / prefetch / fact-meta 플래그 | `demo.jev.choice.enabled`, `demo.jev.prefetch.enabled`, `demo.jev.fact-meta.enabled` |
| 외부 호출 동의 | `demo.jev.fact-meta.external-consent=true` |
| surface 모드 | `JevSurfacePolicy` `main` = `on` (기본 `off`) |
| 스코프 | `JevDecisionScope` 열림, surface `main`, `privacyAllowed` |
| 예산 | `demo.jev.fact-meta.baseline-reserve-ms` > 0 이고 잔여 예산 > reserve |
| 라벨 임계값 | `demo.jev.fact-meta.probability-threshold.<label>` 3종 모두 설정 |
| 위생 | sanitizer 통과 후 정규화 문자열과 byte-동일(절단·마스킹 시 baseline) |
| 단발성 | `scope.claimFactMeta()` — 요청당 1회 |

## 4. Fail-soft 원칙

- 전송·스키마·분포·임계값·상태 래치(auth_blocked/plan_gate/rate_limited/
  budget_skip) 실패는 전부 `Optional.empty()` → 기존 highModel 호출.
  제품 동작은 Jev 유무와 무관하게 보존된다.
- 응답 계약: `answers.factMeta.choice ∈ {CONSISTENT, MISMATCH, INSUFFICIENT}`;
  `probabilities`는 3라벨 정확 집합·선택 라벨=최댓값·합≈1(`1e-6`) —
  `JevGatewayClient.validMetaDistribution()`. `confidenceAccepted`는
  `probability >= threshold(choice)`일 때만 true (`JevEvaluationRuntime.await()`).
- 401 `auth_invalid`, 402 `billing-blocked`, 403 `plan_gate|permission_denied`,
  429 `rate_limited`(+Retry-After ≤300s 래치), 5xx `upstream_error`,
  기타 `http_<n>` — 모드와 무관하게 baseline 폴백.

## 5. 로그·프라이버시

- raw query/context 문자열 로그 금지 — `SafeRedactor.hashValue`·길이만
  (기존 `FactVerifierService` 로그 관례와 동일).
- 인증 값은 env 이름(`demo.jev.credential-env`, 기본 `AI_GATEWAY_API_KEY`)만
  언급; 리다이렉트 미추적으로 Authorization 누출 차단(`JevGatewayClient:21-22,45`).
- 비용 필드 `providerMetadata.gateway.cost`는 문자열 BigDecimal ≥0;
  결측(`null`)은 관측 실패가 아니라 `billedUsd` empty다.

## 6. 비용 전제

- 무료 프로모 윈도우 종료: `demo.jev.free-window-end` 기본 `2026-09-26`
  (`JevEvaluationRuntime.java:268-277`). 무료 가정 금지 —
  `paidAPIbenchmark`는 사용자 명시 승인 전까지 `NOT_RUN`.
- 오프라인 추정 도구: `python -B scripts/eval_jev_fact_meta_budget.py`
  (Jev 단가는 픽스처 평균, baseline 단가·지연은 ASSUMPTION 표기).

## 7. Companion 도구 (Devin 산출, 제품 소스 diff 0)

| 도구 | 역할 |
|---|---|
| `data/agent-handoff/codex-jev-assist/jev_fact_meta_fixtures.json` | 정상·스키마 오류·429·403 plan gate·cost 결측 8종 모의 응답 |
| `scripts/verify_jev_fact_meta_contract.py` | 픽스처 → 판정 체인 재현 검증 (exit 0 = 8종 일치) |
| `scripts/check_jev_nightmare_boundary.py` | B1~B4 경계 위반 0 검사 |
| `scripts/eval_jev_fact_meta_budget.py` | 실패율별 fallback 가산 비용·지연·false-MISMATCH 시뮬레이션 |
| `data/agent-handoff/codex-jev-assist/handoff_card.md` | 인계 카드 |
| `data/agent-handoff/codex-jev-assist/hold_ledger.md` | live 벤치마크 HOLD 조건 |
