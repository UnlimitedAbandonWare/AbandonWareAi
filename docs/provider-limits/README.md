# Provider limits reference SSOT

```yaml
capturedAt: "2026-09-24"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 365
expiresAt: "2027-10-05"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "official_public_documentation"
reviewAfterDays: 365
cadence: "evergreen"
stability: "high"
decayRate: "low"
stabilityReason: "공급자 한도 체계의 메타 거버넌스 규약으로 개별 API 변경과 무관한 아키텍처 SSOT"
runtimeEnforcement: "unchanged"
disclaimer: >
  This directory is a read-only documentation SSOT, not a live entitlement or balance check.
  Exact account limits, remaining credits, and key validity are unknown unless separate
  account evidence is provided. Do not treat these pages as a runtime admission proof.
note: >
  2026-10-05에 OpenAI/Gemini/Groq/Vercel AI Gateway 4개 공식 문서를 재캡처했다.
  같은 날 Brave·Naver·Vercel 가격 문서도 재확인됨(provider-specs-90d-refresh).
  Tavily/OpenRouter 문서는 이날 재캡처하지 않았으므로 본 README의 dir-level
  capturedAt은 2026-09-24를 유지한다(per-provider 문서의 capturedAt을 따를 것).
sourceUrls:
  - "https://console.groq.com/docs/rate-limits"
  - "https://ai.google.dev/gemini-api/docs/rate-limits"
  - "https://ai.google.dev/gemini-api/docs/billing"
  - "https://developers.openai.com/api/docs/guides/rate-limits"
  - "https://developers.openai.com/api/docs/guides/error-codes"
  - "https://developers.openai.com/api/docs/guides/prompt-caching"
  - "https://developers.openai.com/api/docs/models"
  - "https://developers.openai.com/api/docs/models/text-embedding-3-small"
  - "https://vercel.com/docs/ai-gateway"
  - "https://vercel.com/docs/ai-gateway/security-and-compliance"
  - "https://brave.com/search/api/"
  - "https://docs.tavily.com/documentation/api-credits"
  - "https://docs.tavily.com/documentation/rate-limits"
  - "https://developers.naver.com/products/intro/plan/plan.md"
  - "https://developers.naver.com/docs/serviceapi/search/web/web.md"
  - "https://vercel.com/docs/ai-gateway/pricing"
  - "https://vercel.com/docs/ai-gateway/rate-limits"
```

## Purpose

API 호출·모델 접근·비용·한도와 관련된 작업에서만 이 문서들을 참고한다.
일반 UI·빌드·로컬 코드 작업에 전체 provider 조사를 선행 조건으로 걸지 마라.

- 공개 자료만 확인하고, 확인할 수 없는 계정 정보는 `unknown`/`not_published`/`stale`으로 남긴다.
- `unknown`은 정상 상태다. 이를 로그인·요금제 퀴즈·브라우저 설치 사유로 자동 전환하지 마라.
- 90일은 프로젝트 재검토 기준이며, 공급자 보증 기간이나 런타임 증빙 유효기간이 아니다.

## When to read / refresh

- 작업이 API 호출·모델·검색·임베딩·비용·라우팅과 관련될 때만 해당 provider 문서를 연다.
- `capturedAt`이 90일 이내이고 모순 증거가 없으면 기존 자료를 재사용한다.
- 429/401/403, 모델 접근 실패, 문서 없음, 90일 경과 등이 있어도 브라우저 설치·콘솔 로그인·Billing 스크레이핑·요금제 퀴즈를 자동으로 허용하지 마라.
- 공개 페이지가 로그인 화면으로 바뀌면 미확인으로 기록하고 중단한다.

## TTL / 90일 만료 계약 (2026-10-05 도입)

- 각 문서 frontmatter에 `ttlDays: 90`과 `expiresAt`(마지막 유효일, Asia/Seoul)을 둔다.
  `expiresAt` 경과 시 문서는 **STALE_DISCARD** — `status` 필드가 `ACTIVE`로 남아 있어도
  효력을 잃으며 에이전트는 이를 근거로 인용하지 않는다.
- 수명주기 도구: `python -B scripts/provider_limits_janitor.py`
  - `check` — 만료 문서가 있으면 exit 1, 없으면 exit 0 (CI/스모크 연동용).
  - `status` — 문서별 `capturedAt`/`expiresAt`/잔여일/`ACTIVE|EXPIRED|NO_TTL` 표 출력.
  - `archive` — 만료 문서를 `docs/provider-limits/archive/`로 이동하고 워터마크를 단다
    (지시서 ASK_ONCE 기본값 (a) archive 격리 채택; 파일 유지+워터마크 방식 아님).
- `expiresAt` 없는 문서(현재 brave/tavily, groq-free stub)는 `NO_TTL`로만
  표시하고 `check`를 실패시키지 않는다 — 단, 4대 핵심 문서 갱신 시 함께 TTL을 부여하는 것을 권장.
- 만료 전 갱신 절차: 공식 문서를 실제로 다시 읽고 → `capturedAt`/`reviewedAt`을 오늘로,
  `expiresAt`을 `capturedAt + ttlDays`로 갱신한다. 날짜만 찍는 재발급은 금지(Date rules 준수).
- 이 계약은 **문서 신선도**에만 적용된다. 런타임 admission·가드(`GroqFreeTierGuard`의
  계정 증빙 max-age)나 API 키 만료와는 별개다.
- **상시/준영구(Evergreen/Stable) 문서 주기 완화 규정** (2026-10-05 추가): 외부
  벤더의 가격·한도 변동성이 높은 문서(OpenAI/Gemini/Groq/Vercel/Brave)는 기본
  90일 TTL을 유지하되, 내부 런타임 규약(`docs/specs/CODEX_OAUTH_AND_RUNTIME_SPECS.md`),
  데스크톱 라우팅(`openrouter-desktop-routing.md`), 장기 불변 쿼터
  (`naver-search-limits.md`), 상위 거버넌스(본 README)에는 `cadence: evergreen`/
  `semi_permanent` 및 `ttlDays: 365`·`reviewAfterDays: 365` 연장 주기를 적용해
  불필요한 stale 경고를 방지한다. 대상 판정 근거는 각 문서의
  `stability`/`decayRate`/`stabilityReason` 필드에 기록한다.

## Evidence tiers — do not mix

| Tier | Meaning | How we record it |
|---|---|---|
| public policy | 공식 공개 문서의 모델·티어·한도·단가 | `sourceType: official_public_documentation` |
| user statement | 사용자가 직접 말한 현재 플랜 | `accountPlan: <value> (user_statement)` |
| account evidence | 실제 제공된 비밀 제거 계정 증빙 | `accountEvidenceCapturedAt`, `accountExactLimits` |
| runtime observation | 해당 요청·모델·시점의 응답 | 일시적; 영구 한도로 쓰지 않는다 |
| project policy | 라우팅 분류·자체 throttle·spend-guard | 별도 문서/소스 참조 |

API 키 이름·존재, `free_local`/`low_cost`/`paid_quality` 분류, 모델의 `openai/` 접두사,
`ChatGPT` 구독 여부만으로 무료 가격·잔여 크레딧·실행 권한을 단정하지 마라.

## Important conflict with runtime guards

`main/java/com/example/lms/agent/GroqFreeTierGuard.java`는 계정 증빙에 설정 가능한
`groq.free-tier.evidence-max-age-ms`(기본 **90일**)를 적용한다. 이는 API 키 만료나
공개 문서 재검토 날짜와 별개이며, 명시적 proof 만료 검사도 유지한다.

- 공개 문서 확인만으로 Groq 증빙 JSON을 생성/갱신하거나 가드를 해제하지 마라.
  증빙 TTL 정책 변경은 명시적 사용자 요청과 소스·회귀 검증을 따른다.
- 모델 자동 활성화·라우팅 순서 변경·새 hard cap/soft cap·spend-guard 설정 변경도
  이번 범위가 아니다.
- 가드 실패가 `AUTH_MISSING`으로 묶여도 원래 `disabledReason`을 읽는다.
  계정 증빙 만료를 API 키 누락이나 로그인 필요로 단정하지 마라.

이 충돌이 남으면 보고서에 "문서 정책 정리 완료 / 런타임 admission 미변경"이라고 적는다.

## Documents

| 문서 | Env | 엔드포인트 | 주력 모델/용도 (2026-10-05 기준) |
|---|---|---|---|
| [`openai-api-limits.md`](./openai-api-limits.md) | `OPENAI_API_KEY` | `api.openai.com/v1` | `gpt-6-astra`/`gpt-6.1-sol`/`gpt-6-luna`, embed `text-embedding-3-small` |
| [`gemini-limits.md`](./gemini-limits.md) | `GEMINI_API_KEY` | Gemini API (`generativelanguage`) | Gemini 3.x/2.5/2.0 계열, project단위 RPM/TPM/RPD |
| [`vercel-ai-gateway-limits.md`](./vercel-ai-gateway-limits.md) | `AI_GATEWAY_API_KEY` | `ai-gateway.vercel.sh/v1` | `provider/model` 라우팅, Jev decision 전용, budget 기반 |
| [`groq-limits.md`](./groq-limits.md) | `GROQ_API_KEY` | `api.groq.com/openai/v1` | `openai/gpt-oss-*`, `qwen3.8-27b`, whisper — org단위 한도 |
| [`brave-search-limits.md`](./brave-search-limits.md) | `BRAVE_API_KEY_FREE`→`BRAVE_API_KEY` | `api.search.brave.com/res/v1` | 검색 API 듀얼 키(Free $5/월→Base 종량 $5/1,000req·50QPS) |
| [`tavily-limits.md`](./tavily-limits.md) | `TAVILY_API_KEY` | Tavily Search | 검색 API (TTL 미부여 — 아래 계약 참조) |
| [`naver-search-limits.md`](./naver-search-limits.md) | `NAVER_CLIENT_ID`+`NAVER_CLIENT_SECRET`/`NAVER_KEYS` | `openapi.naver.com/v1/search` | 검색 API 일 25,000회 무료 · Brave 격리(4순위 폴백) |
- [`openrouter-desktop-routing.md`](./openrouter-desktop-routing.md) — OpenRouter **Desktop** 라우팅 정책(Space Bunny = secondary pre-screen만; CLI 설치·주력 대체·비밀 전송 금지)

이전 `groq-free-limits.md`는 [`groq-limits.md`](./groq-limits.md)로 병합되었다.
숫자표 중복 없이 링크 호환성을 유지하기 위해 기존 파일은 리디렉션 stub로 남겨둔다.

## Review log

- 2026-10-05 (devin, provider-limits-ttl-2411f85a): OpenAI/Gemini/Groq/Vercel AI Gateway
  4개 공식 문서 재캡처(capturedAt 갱신 정당), `vercel-ai-gateway-limits.md` 신설,
  4개 문서 + 본 README에 `ttlDays/expiresAt/status/expiryAction` 부여.
  확인된 드리프트: Groq `gpt-oss-safeguard-20b` 30→5 RPM·8K→2K TPM, ITPM/OTPM·캐시
  토큰 미계산 신설, Groq 구 llama-3.x 명명 모델 공개 표 부재; Gemini 라인업 3.x 시대
  (3.8 Flash 배너), Priority inference 0.3×·Batch 상한; OpenAI GPT-6 Astra/Sol/Luna
  라인업, prompt caching write 1.25×/read 0.1×(최대 ~95%), spend-limit alert/hard 구분;
  Vercel gateway-기본 ZDR·provider-ZDR 플래그·allowlist Pro/Enterprise 전용.
- 2026-10-05 (devin, provider-specs-90d-refresh-8d75b82c): `naver-search-limits.md`
  신설(검색 API 일 25,000회 공식 확인, `NAVER_*` 자격·Brave 격리), Brave 공식
  가격 재확인(`capturedAt` 갱신 정당) 후 Free→Base 듀얼 키 규격 보강, 90일 만료
  자동 갱신 프로토콜·`scripts/verify_provider_limits_freshness.py` 추가.
  참고: 위 TTL 작업 로그의 `vercel-ai-gateway-limits.md` 신설 기록은 파일
  부재 상태에서 선기록된 것으로 보이며, 실제 파일 작성은 본 작업에서 이뤄졌다.
- 2026-10-01 (devin, zero-cost-audit-1001-ad4cad7a): Brave `monthlyQuota` 2000↔공식
  1,000회 불일치 경고, Groq guard 실효 한도·`qwen/qwen3.8-27b` 미등록, Gemini
  throttle fail-open 주의, Tavily 구현체 2원화(basic/advanced credits) 반영.
  공개 가격·한도 원자료 재캡처 없음 — `capturedAt` 보존, `reviewedAt`만 갱신.

## 90-day expiry auto-refresh protocol (U-2a, 2026-10-05)

`reviewAfterDays`(기본 90)와 `ttlDays`/`expiresAt`은 재검토 주기·문서 신선도
계약이지 런타임 증빙 TTL이 아니다. 만료 감지·갱신은 아래 순서를 따르며,
로그인·결제·플랜 퀴즈를 유도하지 않는다.

1. 탐지: `python -B scripts/verify_provider_limits_freshness.py --check`
   — 만료 문서 목록, 각 `sourceUrls`, 추천 웹서치 키워드를 반환한다(exit 0).
2. 탐침: 만료 문서의 공식 `sourceUrls` 첫 항목을 웹서치 1회로 재확인한다.
3. 변동 없음 → `reviewedAt`만 당일로 갱신하고 `expiresAt`을 연장해 90일을
   자동 연장한다(`capturedAt` 보존). 사용자 승인 퀴즈 없음.
4. 변동 있음 → 본문 수치·정책을 반영한 뒤 `capturedAt`과 `reviewedAt`을
   함께 갱신하고, review log에 근거를 남긴다.

## Date rules

- 공개 자료를 오늘 실제로 읽었다면 그 자료의 `capturedAt`은 재캡처 당일로 기록할 수 있다
  (예: 초기 캡처 `2026-09-24`, 재캡처 `2026-10-05`).
- 과거 사용자 캡처나 코드의 날짜(예: `2026-09-16`)를 오늘의 계정 확인값으로 다시 날짜 찍지 마라.
- 계정 증빙이 없으면 `accountEvidenceCapturedAt`은 `null` 또는 `unknown`으로 남긴다.
- 단순 검토만 했다면 `reviewedAt`만 갱신하고 원래 `capturedAt`은 보존한다.

## Completion criteria

- 각 provider 문서와 본 README에 `capturedAt`, `timezone`, `reviewedAt`, `sourceType`,
  `sourceUrls`, `disclaimer`가 있다.
- `accountPlan`/`accountExactLimits`/`credentialStatus`가 증거 유무에 맞게 구분된다.
- 런타임 설정/소스 변경 없이 문서만 정리했다.

## Source URLs consulted

2026-10-05 (4대 API 재캡처):

- Groq rate limits: <https://console.groq.com/docs/rate-limits>
- Gemini rate limits: <https://ai.google.dev/gemini-api/docs/rate-limits>
- OpenAI rate limits: <https://developers.openai.com/api/docs/guides/rate-limits>
- OpenAI models: <https://developers.openai.com/api/docs/models>
- OpenAI prompt caching: <https://developers.openai.com/api/docs/guides/prompt-caching>
- Vercel AI Gateway: <https://vercel.com/docs/ai-gateway>
- Vercel AI Gateway security/compliance: <https://vercel.com/docs/ai-gateway/security-and-compliance>
- Vercel AI Gateway pricing: <https://vercel.com/docs/ai-gateway/pricing>
- Vercel AI Gateway rate limits: <https://vercel.com/docs/ai-gateway/rate-limits>
- Brave Search API (재확인): <https://brave.com/search/api/>
- Naver API plan list: <https://developers.naver.com/products/intro/plan/plan.md>
- Naver web search docs: <https://developers.naver.com/docs/serviceapi/search/web/web.md>

2026-09-24 (초기 캡처, Brave/Tavily 포함):

- Gemini billing: <https://ai.google.dev/gemini-api/docs/billing>
- OpenAI error codes: <https://developers.openai.com/api/docs/guides/error-codes>
- OpenAI `text-embedding-3-small`: <https://developers.openai.com/api/docs/models/text-embedding-3-small>
- Brave Search API: <https://brave.com/search/api/>
- Tavily credits: <https://docs.tavily.com/documentation/api-credits>
- Tavily rate limits: <https://docs.tavily.com/documentation/rate-limits>
