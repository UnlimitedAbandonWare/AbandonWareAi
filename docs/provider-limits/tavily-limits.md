# Tavily limits (SSOT)

```yaml
capturedAt: "2026-09-24"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-01"
sourceType: "official_public_documentation"
accountPlan: "unknown"
accountEvidenceCapturedAt: null
accountExactLimits: "unknown"
remainingCredits: "unknown"
credentialStatus: "not_checked"
reviewAfterDays: 90
runtimeEnforcement: "unchanged"
disclaimer: >
  This page records public Tavily pricing/credits/rate-limit information as of 2026-09-24.
  It is not an account statement or a current-balance/remaining-credit check.
  Monthly credit allowance is not the same as allowed request count.
sourceUrls:
  - "https://docs.tavily.com/documentation/api-credits"
  - "https://docs.tavily.com/documentation/rate-limits"
```

## What this file is / is not

- `capturedAt`은 공개 문서 확인일이다. 현재 계정의 플랜·잔여 credits·RPM은 미확인이다.
- 월간 credits와 요청 횟수는 같은 값이 아니다. 기능/옵션에 따라 요청당 소진 credits가 다르다.

## Public pricing / credits

| Plan | Credits per month | Monthly price | Price per credit |
|---|---|---|---|
| Researcher | 1,000 | Free | - |
| Project | 4,000 | $30 | $0.0075 |
| Bootstrap | 15,000 | $100 | $0.0067 |
| Startup | 38,000 | $220 | $0.0058 |
| Growth | 100,000 | $500 | $0.005 |
| Pay as you go | Per usage | $0.008 / credit | $0.008 |
| Enterprise | Custom | Custom | Custom |

## Credits per request type

### Tavily Search

- `basic` search: **1 credit / request**
- `advanced` search: **2 credits / request**

Repo 구현체 2원화(2026-10-01 확인):

| 구현체 | search_depth | 요청당 credits |
|---|---|---|
| `com.abandonware.ai.agent.integrations.TavilyWebSearchRetriever` (:86) | `"advanced"` 하드코딩 | 2 |
| `com.example.lms.service.rag.TavilyWebSearchRetriever` | 미전송 → API 기본값 `basic` | 1 |

`configs/api-routing.yaml`의 tavily `seams`가 abandonware 구현체를 가리키므로
해당 경로는 호출당 2 credits를 소모한다. RAG 경로(basic)와의 선택은 라우팅 정책 사안이다.

### Tavily Extract

- `basic` extract: **1 credit / 5 successful URL extractions**
- `advanced` extract: **2 credits / 5 successful URL extractions**

### Tavily Map

- Regular mapping: **1 credit / 10 successful pages returned**
- Map with instructions: **2 credits / 10 successful pages returned**

### Tavily Crawl

Cost = mapping cost + extraction cost for the crawled pages.

## Rate limits

Limits are per environment (`Development` / `Production`).

| Endpoint | Development RPM | Production RPM |
|---|---|---|
| Default | 100 | 1,000 |
| Crawl | 100 | 100 |
| Research | 20 | 20 |
| Usage endpoint | 10 / 10 minutes | 10 / 10 minutes |

Production key access requires an active paid plan or PAYGO enabled.

## Credential

- Canonical env: `TAVILY_API_KEY` (값은 기록하지 않음).

## Agent guidance

- API 관련 작업에서만 이 문서를 읽는다.
- 월 1,000 free credits이 있다고 해서 1,000 requests라고 단정하지 마라.
- 429 발생 시 `Retry-After` 헤더를 확인하고, 원인 코드/환경별 RPM을 먼저 기록한다.
- 키가 없거나 활성 상태를 확인하지 않았어도 문서 작성은 계속할 수 있다.
  가입·로그인·발급 퀴즈는 하지 않는다.
