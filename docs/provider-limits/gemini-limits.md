# Gemini limits (SSOT)

```yaml
capturedAt: "2026-09-24"
timezone: "Asia/Seoul"
reviewedAt: "2026-09-24"
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

## What this file is / is not

- `capturedAt`은 공개 문서 확인일이다. 사용자의 현재 플랜이나 결제 상태는 증거 없이
  `unknown`으로 둔다.
- "유료라고 가정하지 않는다"는 "Free로 확정"과 다르다. Free/유료 여부와 모델별 정확한
  quota는 현재 미확인이다.
- 제3자가 추정한 "2.5 Pro 5 RPM" 같은 값은 공식 문서가 아니므로 넣지 않는다.
- `main/java/com/example/lms/agent/FreeTierApiThrottleService.java`의 기본값
  60 RPM / 1,000 RPD는 **앱 자체의 선택적 로컬 throttle**이며, Google의 공식 한도가 아니다.

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
or for individual accounts. Active model-specific limits are shown in **Google AI Studio**.
The AI Studio reference is a location description, not a login instruction.

```yaml
perModelAccountLimits: "not_published_here / account_exact_unknown"
```

## Credential

- Canonical env: `GEMINI_API_KEY` (값은 기록하지 않음).
- `GEMINI_API_KEY` 존재만으로 Free/유료 플랜·잔여량·실행 권한을 단정하지 마라.

## Agent guidance

- API 관련 작업에서만 이 문서를 읽는다.
- 90일 이내이고 공식 정책 변경 증거가 없으면 재사용한다.
- 429 `RESOURCE_EXHAUSTED`가 발생하면 error 세부 코드·헤더·Retry-After를 먼저 확인하고,
  모델/프로젝트 범위로 기록한다. 모든 429를 "요금제 변경"으로 단정하지 마라.
