# OpenAI API limits (SSOT)

```yaml
capturedAt: "2026-09-24"
timezone: "Asia/Seoul"
reviewedAt: "2026-09-24"
sourceType: "official_public_documentation"
accountPlan: "unknown"
accountEvidenceCapturedAt: null
accountExactLimits: "unknown"
accountUsageTier: "unknown"
credentialStatus: "not_checked"
reviewAfterDays: 90
runtimeEnforcement: "unchanged"
disclaimer: >
  This page records only public OpenAI API documentation. It is not an account entitlement,
  billing confirmation, key-validity check, or live quota measurement. The presence of
  OPENAI_API_KEY (or its aliases) does not prove a paid plan, free credits, or remaining balance.
sourceUrls:
  - "https://developers.openai.com/api/docs/guides/rate-limits"
  - "https://developers.openai.com/api/docs/guides/error-codes"
  - "https://developers.openai.com/api/docs/models/text-embedding-3-small"
```

## What this file is / is not

- `capturedAt`은 공개 문서 확인일이다. 현재 계정의 usage tier, 결제 상태, 잔여 크레딧은
  `unknown`이다.
- `ChatGPT` 구독과 OpenAI API 결제·크레딧은 별개다.
- Canonical env는 `OPENAI_API_KEY`이며, 실제 resolver는 아래 기존 alias들도 읽는다.
  키 이름의 존재만으로 무료·유료·잔여량·실행 권한을 단정하지 마라.

## Credential env names

Canonical env: `OPENAI_API_KEY`

`ProviderCredentialResolver.java`의 기존 alias (이름만 기록, 값은 기록하지 않음):

- `llm.api-key-openai`
- `llm.openai.api-key`
- `OPENAI_API_KEY`
- `openai.api.key`
- `openai.api-key`
- `spring.ai.openai.api-key`
- `openai.image.api-key`
- `embedding.fallback.api-key`
- `OPENAI_EMBED_FALLBACK_KEY`

여러 alias가 동시에 설정되어 있고 값이 다르면 resolver는 충돌로 처리할 수 있다.
`OPENAI_API_KEY` 하나가 unset이라고 키가 전혀 없다고 단정하지 마라.

## Usage tiers

| Tier | Qualification | Usage limit |
|---|---|---|
| Free | Allowed geography | $100 / month |
| Tier 1 | $5 paid | $100 / month |
| Tier 2 | $50 paid | $500 / month |
| Tier 3 | $100 paid | $1,000 / month |
| Tier 4 | $250 paid | $5,000 / month |
| Tier 5 | $1,000 paid | $200,000 / month |

Rate limits vary by model and are set at the organization/project level, not user level.
Exact limits for this account are shown in the account's **Limits** page; they are not captured here.

## Model-specific public reference

### `text-embedding-3-small`

| Tier | RPM | RPD | TPM | Batch queue limit |
|---|---|---|---|---|
| Free | 100 | 2,000 | 40,000 | - |
| Tier 1 | 3,000 | - | 1,000,000 | 3,000,000 |
| Tier 2 | 5,000 | - | 1,000,000 | 20,000,000 |
| Tier 3 | 5,000 | - | 5,000,000 | 100,000,000 |
| Tier 4 | 10,000 | - | 5,000,000 | 500,000,000 |
| Tier 5 | 10,000 | - | 10,000,000 | 4,000,000,000 |

**Pricing**: `$0.02 / 1M tokens` for embeddings.

Free tier limit table does **not** mean the price is $0. `Free tier` is a usage tier name.
Other OpenAI chat/completion models are not listed here because no specific Free tier
public quota table was confirmed for them in this review; treat them as `not_published_here / unknown`.

## Error code guidance

HTTP 429 alone is not a single failure type. Read `error.code` / `error.type` and any
`Retry-After` header:

| Status | Type | Code | Meaning |
|---|---|---|---|
| 429 | `insufficient_quota` | `credit_balance_exhausted` | No prepaid credits remain |
| 429 | `insufficient_quota` | `organization_spend_limit_exceeded` | Org spend limit reached |
| 429 | `insufficient_quota` | `project_spend_limit_exceeded` | Project spend limit reached |
| 429 | `insufficient_quota` | `organization_usage_limit_reached` | OpenAI-assigned usage limit reached |
| 429 | `rate_limit_error` | `rate_limit_reached` | Too many requests |
| 429 | `rate_limit_error` | `slow_down` | Traffic increased too quickly |

Retrying billing/spend/quota errors does not restore access; the cause must be resolved first.
Do not treat every 429 as a plan-change or login request.

## Agent guidance

- API 관련 작업에서만 이 문서를 읽는다.
- `capturedAt`이 90일 이내이고 공식 정책 변경 증거가 없으면 재사용한다.
- 문서 갱신 자체가 API 키 유효성이나 계정 접근 성공을 증명하는 것은 아니다.
