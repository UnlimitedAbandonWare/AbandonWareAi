# Brave Search limits (SSOT)

```yaml
capturedAt: "2026-09-24"
timezone: "Asia/Seoul"
reviewedAt: "2026-09-24"
sourceType: "official_public_documentation"
accountPlan: "unknown"
accountEvidenceCapturedAt: null
accountExactLimits: "unknown"
remainingCredits: "unknown"
credentialStatus: "not_checked"
reviewAfterDays: 90
runtimeEnforcement: "unchanged"
disclaimer: >
  This page records public Brave Search API pricing/capacity as of 2026-09-24.
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

## Project routing policy

- Routing YAML (`configs/api-routing.yaml`) classifies Brave as `free_local`.
- `docs/codex/BRAVE_FREE_TO_BASE_ROUTING_DIRECTIVE.md` defines the dual-key behavior:
  `BRAVE_API_KEY_FREE` first up to a local monthly quota, then `BRAVE_API_KEY` base.
  This is an application policy, not Brave's official plan classification.

## Credential env names

- Canonical / first lane: `BRAVE_API_KEY_FREE`
- Base lane: `BRAVE_API_KEY`
- Retired / never read: `BRAVE_SUBSCRIPTION_TOKEN`

키 값은 기록하지 않는다.

## Agent guidance

- API 관련 작업에서만 이 문서를 읽는다.
- `capturedAt`이 90일 이내이고 공식 정책 변경 증거가 없으면 재사용한다.
- 키가 없거나 상태가 미확인이어도 문서 작성은 계속할 수 있다. 가입·로그인·발급 퀴즈를
  하지 않는다.
