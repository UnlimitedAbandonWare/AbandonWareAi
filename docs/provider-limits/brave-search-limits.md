# Brave Search limits (SSOT)

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
remainingCredits: "unknown"
credentialStatus: "not_checked"
reviewAfterDays: 90
runtimeEnforcement: "unchanged"
disclaimer: >
  This page records public Brave Search API pricing/capacity as of 2026-10-05.
  It is not an account statement, free-plan confirmation, or key-validity check.
  Env names like BRAVE_API_KEY_FREE are routing aliases; they do not prove the current
  plan or remaining credits by themselves.
sourceUrls:
  - "https://brave.com/search/api/"
  - "https://api-dashboard.search.brave.com/documentation/guides/rate-limiting"
```

## What this file is / is not

- `BRAVE_API_KEY_FREE`라는 이름이 있거나 `free_local` 라우팅 분류가 있다고 해서
  현재 계정이 무료 플랜이거나 무료 잔여 크레딧이 있는 것이 자동으로 보장되지 않는다.
- `BRAVE_SUBSCRIPTION_TOKEN`은 사용되지 않는 예전 env 이름이다.
- 현재 계정의 플랜·잔여 크레딧·정확한 RPM은 `unknown`이다.

## Public pricing / capacity

| Product | Price | Free monthly credits | Capacity |
|---|---|---|---|
| Search | $5 / 1,000 requests | $5 in free credits | 50 queries/second |
| Answers | $4 / 1,000 requests + $5 / 1M input/output tokens | $5 in free credits | 2 queries/second |
| Enterprise | Custom | Custom | Custom |

Free credits are advertised as "$5 in free credits every month" and applied automatically.
This is a public pricing description, not this account's current balance.

## Project routing policy — dual key (Free → Base)

- Routing YAML (`configs/api-routing.yaml`) classifies Brave as `free_local`,
  env `[BRAVE_API_KEY_FREE, BRAVE_API_KEY]`, auth header `X-Subscription-Token`.
- **Free lane** `BRAVE_API_KEY_FREE`: official free monthly credit **$5 ≈
  1,000 requests** (at $5/1,000 req). Consumed first up to
  `gpt-search.brave.monthly-quota`.
- **Base lane** `BRAVE_API_KEY`: same-host Brave paid tier, official list
  **$5 / 1,000 requests, 50 queries/second** pay-as-you-go. On free-quota
  exhaustion or a provider `402`/`429` signal the same request promotes to the
  base key — pre-approved 2026-09-30, no per-call ask
  (`docs/API_ROUTING_SPEC.md` env table).
- Promotion never disables Brave and never jumps to Naver/Tavily on quota
  grounds (`docs/agents-rules/DEMO1-BRAVE-DUAL-KEY.md`). Contract:
  `docs/codex/BRAVE_FREE_TO_BASE_ROUTING_DIRECTIVE.md`. This is an application
  policy, not Brave's official plan classification.

## Local quota vs official credits (reviewed 2026-10-01)

- `main/java/com/example/lms/service/web/BraveSearchProperties.java:12`의
  `monthlyQuota` 기본값은 `2000`이다(`gpt-search.brave.monthly-quota`).
- 공식 무료 크레딧은 **월 $5 = 1,000 쿼리**(`$5 / 1,000 requests`)이므로 기본값과
  1,000회 불일치가 있다. 1,001~2,000 구간에서 `BRAVE_API_KEY_FREE`가 실제로는
  소진된 뒤 `BRAVE_API_KEY` base lane(유료)으로 자동 전환될 수 있다.
- 코드 기본값 조정 전까지 권장 설정: `gpt-search.brave.monthly-quota: 1000`
  (환경변수 `GPT_SEARCH_BRAVE_MONTHLYQUOTA` 계열 relaxed binding).
  기본값 1000 조정은 Codex 인계 항목(`data/agent-handoff/zero-cost-audit/FOR_CODEX.md`).

## Credential env names

- Canonical / first lane: `BRAVE_API_KEY_FREE`
- Base lane: `BRAVE_API_KEY`
- Retired / never read: `BRAVE_SUBSCRIPTION_TOKEN`

키 값은 기록하지 않는다.

## Agent guidance

- API 관련 작업에서만 이 문서를 읽는다.
- `capturedAt`이 90일 이내이고 공식 정책 변경 증거가 없으면 재사용한다.
- 90일 재검토: `python -B scripts/verify_provider_limits_freshness.py --check`
  가 만료 여부·`sourceUrls`·추천 웹서치 키워드를 반환한다. 변동 없으면
  `reviewedAt`만 당일로 갱신해 90일을 연장하고, 변동이 있으면 본문 반영 후
  `capturedAt`을 갱신한다 (README "90-day expiry auto-refresh protocol").
- 키가 없거나 상태가 미확인이어도 문서 작성은 계속할 수 있다. 가입·로그인·발급 퀴즈를
  하지 않는다.
