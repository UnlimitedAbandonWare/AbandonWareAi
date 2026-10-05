# Naver Search limits (SSOT)

```yaml
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 365
expiresAt: "2027-10-05"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "official_public_documentation"
accountPlan: "unknown"
accountEvidenceCapturedAt: null
accountExactLimits: "unknown"
remainingCredits: "unknown"
credentialStatus: "not_checked"
reviewAfterDays: 365
cadence: "semi_permanent"
stability: "high"
decayRate: "low"
stabilityReason: "네이버 개발자 센터 검색 오픈 API의 1일 25,000건 무료 쿼터는 수년간 지속된 장기 불변 정책"
runtimeEnforcement: "unchanged"
disclaimer: >
  This page records public Naver Search Open API quota/contract information as of
  2026-10-05. It is not an account statement, app-registration check, or
  key-validity check. Env names like NAVER_CLIENT_ID are credential identifiers;
  they do not prove the registered app's current quota or remaining calls.
  Naver API terms restrict how results may be reused: this project uses them only
  as real-time web-search retrieval input for answer generation, never for model
  training, dataset construction, or bulk crawling/storage.
sourceUrls:
  - "https://developers.naver.com/products/intro/plan/plan.md"
  - "https://developers.naver.com/products/service-api/search/search.md"
  - "https://developers.naver.com/docs/serviceapi/search/web/web.md"
  - "https://developers.naver.com/docs/serviceapi/search/blog/blog.md"
  - "https://developers.naver.com/apps/#/myapps"
```

## What this file is / is not

- `capturedAt`은 공식 공개 문서 확인일(2026-10-05 웹서치)이다. 현재 계정의 등록 앱,
  일일 잔여 호출 수, 인증 상태는 미확인이다.
- Naver `NAVER_*` env 는 **Brave 가 아니다**. Brave 무료 쿼터 소진 시 Naver 로의
  자동 failover 는 프로젝트 정책상 금지다(`docs/agents-rules/DEMO1-BRAVE-DUAL-KEY.md`).
- AI 연동 주의사항(U-1a): Naver 검색 결과는 실시간 웹 검색 근거로만 사용한다.
  모델 학습·데이터셋 축적·대량 수집 용도로는 쓰지 않는다.

## Public quota / contract

| Item | Public value | Notes |
|---|---|---|
| 검색 API call limit | **25,000 calls / day** | applies to the Search API family (blog, web, news, image, book, encyclopedia, cafe, kin, shop, local, ...) |
| Monthly arithmetic | ≈ 775,000 calls / 31-day month | pure multiplication of the daily cap, not a published monthly plan |
| Auth | `X-Naver-Client-Id` + `X-Naver-Client-Secret` headers | issued per registered application (NCP/API Hub or developers.naver.com app) |
| Price | Free within the daily cap | no paid per-call tier documented on the public pages checked |

Web-document endpoint used by this repo:

`GET https://openapi.naver.com/v1/search/webkr.json?query=...&display=...`

Other Search API endpoints share the same daily cap (e.g. `/v1/search/blog.json`,
`/v1/search/news.json`, `/v1/search/image`).

## Provider error codes (family)

`scripts/apikit/providers/naver.py` maps the `SE##` error family:

`SE01` key_invalid · `SE02`/`SE03`/`SE05`/`SE06` bad_request_shape · `SE04`
rate_limit (local mapping) · `SE99` upstream 5xx. HTTP auth failures (missing or
wrong client pair) return a 4xx before any `SE##` body. Record the raw code in
diagnostics; do not invent meanings beyond the table.

## Credential env names

- Canonical pair: `NAVER_CLIENT_ID` + `NAVER_CLIENT_SECRET` (headers above).
- Multi-key CSV: `NAVER_KEYS` — comma-separated `id:secret` (or `id;secret`)
  pairs; `NaverSearchService` rotates/falls back across pairs. First parsed pair
  is what `scripts/apikit` probes.
- Values are never recorded.

## Project routing position

- `configs/api-routing.yaml` classifies `naver` as tier `free_local`, seam
  `com.example.lms.service.NaverSearchService`.
- `docs/API_ROUTING_SPEC.md` web-search priority: Brave → Tavily → SerpAPI →
  **Naver (4th, free-ish quota)** → fail-soft disable. Naver is a downstream
  fallback of the chain, never a Brave free-quota replacement.
- `docs/PROJECT_STATUS.md` gap note (zero-cost-audit-1001): "Naver LLM 직결
  정책검토" — a direct-LLM-lane use stays unapproved; search-only is the
  documented role.

## Agent guidance

- API 관련 작업에서만 이 문서를 읽는다.
- `capturedAt`이 90일 이내이고 공식 정책 변경 증거가 없으면 재사용한다.
  만료 감지·갱신 프로토콜: `python -B scripts/verify_provider_limits_freshness.py --check`
  (README "90-day expiry auto-refresh protocol" 참조).
- 키가 없거나 상태가 미확인이어도 문서 작성은 계속할 수 있다. 가입·로그인·발급
  퀴즈는 하지 않는다.
