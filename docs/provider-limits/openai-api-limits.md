# OpenAI API limits (SSOT)

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
  - "https://developers.openai.com/api/docs/guides/prompt-caching"
  - "https://developers.openai.com/api/docs/models"
  - "https://developers.openai.com/api/docs/models/text-embedding-3-small"
```

## 에이전트 빠른 참조

| 항목 | 값 |
|---|---|
| Canonical env | `OPENAI_API_KEY` (값 기록·출력 금지; resolver alias는 아래 섹션) |
| 엔드포인트 | `https://api.openai.com/v1` (Responses API 중심; Chat Completions·Batch·Embeddings 포함) |
| 주력 모델(2026-10-05 공식 문서) | `gpt-6-astra`(플래그십), `gpt-6.1-sol`(균형), `gpt-6-luna`; 장문 컨텍스트 계열 `gpt-5.5`는 별도 rate limit |
| 한도 단위 | RPM/RPD/TPM/TPD/IPM(+일부 오디오 분) — **organization·project 레벨**, user 레벨 아님. "shared limit" 그룹은 모델 간 TPM 공유 |
| 사용량 상한 | org 월 usage limit(tier표 아래)과 별개로 spend limit(alert/hard-429) 설정 가능 |
| 401 | 인증 실패(auth) |
| 403 | 권한/지역·플랜 제한 — body 문구로 구분 |
| 429 | `insufficient_quota.*`(크레딧/spend/usage-limit — 재시도 무의미) vs `rate_limit_error.*`(rate_limit_reached/slow_down — Retry-After 후 재시도) |
| 5xx | `server_is_overloaded` 등 일시 과부하 — Retry-After 헤더 확인 |

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
Other chat/completion models publish per-tier limits on the models/limits pages rather than a
single public table; treat per-model exact values as `account_exact_unknown` unless the org
Limits page is separately evidenced.

### Prompt caching (2026-10-05 확인)

- 지원 모델에서 기본 활성. 최소 캐시 가능 prefix 길이는 **1,024 tokens(GPT-5.6 이후 기준,
  이전 모델은 요청 설정에 따라 다름)**.
- GPT-5.6+: cache **write 1.25×** uncached input rate, cache **read 0.1×**(GPT-6.1 Sol은 0.05×).
  구 문서의 "50% 할인" 표현은 폐기 — 현재는 최대 ~95% 할인(0.05~0.1×) + write 가산 구조.
- prefix가 model·tools·text.format·reasoning.effort·context_management 등 변경 전까지
  동일해야 cache hit. 캐시는 토큰이 아니라 KV state를 저장한다.

### Spend limits (2026-10-05 확인)

- tier별 월 usage limit과 별개로 org/project **spend limit**을 설정할 수 있다:
  spend alert(통지만, 트래픽 지속)와 hard spend limit(도달 시 해당 요청 `429`)으로 나뉜다.

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
- `expiresAt` 경과 시 **STALE_DISCARD** — `scripts/provider_limits_janitor.py archive`가
  `docs/provider-limits/archive/`로 격리한다. 만료 전엔 `capturedAt` 90일 이내 + 공식 정책
  변경 증거 없으면 재사용한다.
- 문서 갱신 자체가 API 키 유효성이나 계정 접근 성공을 증명하는 것은 아니다.
