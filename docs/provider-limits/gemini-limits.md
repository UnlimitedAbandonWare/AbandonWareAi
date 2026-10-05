# Gemini limits (SSOT)

```yaml
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 90
expiresAt: "2027-01-03"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "official_public_documentation"
accountPlan: "unknown"
accountEvidenceCapturedAt: null
accountExactLimits: "unknown"
credentialStatus: "not_checked"
reviewAfterDays: 90
runtimeEnforcement: "unchanged"
disclaimer: >
  This page records only public Gemini API policy. It is not an account entitlement,
  billing confirmation, or live quota measurement. Exact per-project/model limits are
  shown in AI Studio/account settings and are not captured here.
sourceUrls:
  - "https://ai.google.dev/gemini-api/docs/rate-limits"
  - "https://ai.google.dev/gemini-api/docs/billing"
```

## 에이전트 빠른 참조

| 항목 | 값 |
|---|---|
| Canonical env | `GEMINI_API_KEY` (값 기록·출력 금지). 이 repo resolver는 `gemini.api-key`/`gemini.api.key` 프로퍼티도 읽는다. `GOOGLE_API_KEY`는 업스트림 관례 이름일 뿐 이 repo resolver가 읽지 않는다 |
| 엔드포인트 | Gemini API (`generativelanguage.googleapis.com` 계열, SDK가 추상화) |
| 주력 모델(2026-10-05 공식 표 기준) | Gemini 3.x 계열(3.1 Pro Preview, 3.8/3.7/3.6/3.5 Flash, 3.5 Flash-Lite, 3.1 Flash Lite) + 2.5 Pro/Flash/Flash-Lite + 2.0 Flash — 페이지 상단 배너 "Gemini 3.8 Flash is now available" |
| 한도 단위 | RPM / input TPM / RPD — **project 레벨**(키 아님), RPD는 Pacific 자정 리셋. experimental/preview 모델은 더 타이트 |
| Spend cap | rolling 10분 창: Free N/A / Tier1 $10 / Tier2 $50 / Tier3 $200 |
| Priority inference | 소비는 전체 interactive 한도에 합산되지만 자체 한도 = 표준의 **0.3×** |
| Batch | 동시 batch 100건, 입력 파일 2GB, 저장 20GB, 모델별 enqueued-token 상한 별도 표 |
| 429 | `RESOURCE_EXHAUSTED` — spend cap이면 wait+retry·요청 축소·한도 상향 신청 순 |
| 401/403 | 키·권한·지역 문제 — 모두를 "플랜 변경 필요"로 단정 금지 |

## What this file is / is not

- `capturedAt`은 공개 문서 확인일이다. 사용자의 현재 플랜이나 결제 상태는 증거 없이
  `unknown`으로 둔다.
- "유료라고 가정하지 않는다"는 "Free로 확정"과 다르다. Free/유료 여부와 모델별 정확한
  quota는 현재 미확인이다.
- 제3자가 추정한 "2.5 Pro 5 RPM" 같은 값은 공식 문서가 아니므로 넣지 않는다.
- `main/java/com/example/lms/agent/FreeTierApiThrottleService.java`의 기본값
  60 RPM / 1,000 RPD는 **앱 자체의 선택적 로컬 throttle**이며, Google의 공식 한도가 아니다.
- 2026-10-01 주의: 동 서비스는 `@ConditionalOnProperty(
  "gemini.api.free-tier.throttle.enabled", matchIfMissing=false)` 빈이라 프로퍼티
  미설정 시 생성되지 않고, `learning/gemini/GeminiGateway.java:411`의
  `throttle == null || throttle.canProceed()` 때문에 **fail-open**(전부 허용)이 된다.
  고정 윈도우 리셋(분 경계 직후 재카운트)과 기동 시점 카운터 특성도 별도 결함으로
  Codex에 인계한다(`data/agent-handoff/zero-cost-audit/FOR_CODEX.md`).

## Public policy summary

Gemini API rate limits are typically measured as:

- **RPM** — requests per minute
- **input TPM** — input tokens per minute
- **RPD** — requests per day

Limits are applied **per project**, not per API key. RPD resets at **midnight Pacific Time**.
Some models may also have TPD or image-per-minute (IPM) limits.

### Spend-based rate limits

| Usage tier | Spend limit per 10 minutes |
|---|---|
| Free | N/A |
| Tier 1 | $10 |
| Tier 2 | $50 |
| Tier 3 | $200 |

`N/A` for Free tier means there is no spend-based rate limit on that tier; it does **not**
mean there are no other limits.

### Usage tiers

| Usage tier | Qualification | Billing tier cap |
|---|---|---|
| Free | Active project or free trial | N/A |
| Tier 1 | Linked active billing account | $250 |
| Tier 2 | Paid $100 + 3 days from first payment | $2,000 |
| Tier 3 | Paid $1,000 + 30 days from first payment | $20,000 - $100,000+ |

Tier advancement is automatic based on cumulative Google Cloud spending for the linked
billing account. The exact current tier for this project is not known (`unknown`).

## Model-specific limits

Current public docs do not publish a fixed per-model RPM/RPD/TPM table for the Free tier
or for individual accounts — 모델별 interactive 한도는 **Google AI Studio**에만 표시된다
(위치 설명이지 로그인 지시가 아니다). 다만 2026-10-05 기준 공개 페이지는 **Batch API의
모델별 enqueued-token 상한 표**와 현재 모델 라인업(Gemini 3.x/2.5/2.0 계열)은 싣고 있다.
구 `gemini-1.5-*`/`gemini-2.0-flash` 이전 세대 모델명을 주력으로 단정하지 마라 — 최신
모델명은 SSOT 재확인(`$demo1-mutable-spec-policy`) 대상이다.

```yaml
perModelAccountLimits: "not_published_here / account_exact_unknown"
```

## Credential

- Canonical env: `GEMINI_API_KEY` (값은 기록하지 않음).
- `GEMINI_API_KEY` 존재만으로 Free/유료 플랜·잔여량·실행 권한을 단정하지 마라.

## Agent guidance

- API 관련 작업에서만 이 문서를 읽는다.
- `expiresAt`(2027-01-03) 경과 시 **STALE_DISCARD** — `scripts/provider_limits_janitor.py
  archive`가 `docs/provider-limits/archive/`로 격리한다. 만료 전엔 `capturedAt` 90일 이내 +
  공식 정책 변경 증거 없으면 재사용한다.
- 429 `RESOURCE_EXHAUSTED`가 발생하면 error 세부 코드·헤더·Retry-After를 먼저 확인하고,
  모델/프로젝트 범위로 기록한다. 모든 429를 "요금제 변경"으로 단정하지 마라.
