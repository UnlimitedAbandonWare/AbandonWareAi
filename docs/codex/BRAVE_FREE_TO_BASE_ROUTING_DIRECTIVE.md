# Codex 지시서: Brave 무료 → 베이스(유료) 이중 키 라우팅

**대상 루트:** `C:\AbandonWare\demo-1\demo-1\src`  
**작성일:** 2026-09-17  
**목적:** 웹 검색에서 Brave **무료 플랜**을 먼저 쓰고, **월 2,000건**을 넘기면 같은 Brave 엔드포인트의 **베이스(유료) 플랜** 키로만 전환한다. 네이버·Tavily·SerpAPI와 혼동하지 말 것.

관련 SSOT: `docs/API_ROUTING_SPEC.md`, `configs/api-routing.yaml`, 본 문서의 **명세서** 절.

---

## 0. 착각 금지 (필독)

| 구분 | 무엇인가 | 무엇 **아닌가** |
|------|----------|-----------------|
| **Brave Free** | Brave Search **무료 플랜** 구독 토큰. 월 쿼터 **2,000** | 네이버 API가 아님. 다른 검색 벤더가 아님 |
| **Brave Base (Paid)** | Brave Search **유료/베이스 플랜** 구독 토큰. 같은 `api.search.brave.com` | “베이스” ≠ 네이버. “베이스” ≠ Tavily/SerpAPI |
| **Naver** | `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` / `NAVER_KEYS` | Brave의 free/base 티어가 **절대** 아님 |
| **하이브리드 폴백 체인** | Brave(내부 free→base) 실패 후 Tavily → SerpAPI → Naver | free 소진 시 바로 Naver로 뛰는 것이 **아님** (먼저 Brave base) |

**한 줄 요약:** Free와 Base는 **같은 Brave 프로바이더의 서로 다른 구독 키**다. 네이버는 **별도 프로바이더**다.

토큰 문자열을 Java/테스트/로그/트레이스/커밋에 **하드코딩하지 말 것**. 값은 `.env` / `shared.env` / `.secrets` 에만 두고, 코드는 **env 이름**만 본다. 로그는 `keyPresent` / `keySource` / length / hash 만.

---

## 1. 운영자 env 매핑 (현재 인벤토리)

운영자가 채울 변수 (이름은 고정, 값은 시크릿 저장소에만):

| Env 이름 | 역할 | 비고 |
|----------|------|------|
| `BRAVE_API_KEY_FREE` | Brave **무료** 플랜 `X-Subscription-Token` | **신규**. free 우선 사용 |
| `BRAVE_API_KEY` | Brave **베이스(유료)** 플랜 토큰 | **현재** 베이스 키가 여기 있음 |
| `BRAVE_SUBSCRIPTION_TOKEN` | **폐기**된 구형 환경변수명 | 활성 조회·별칭·페일오버에서 제거. 헤더 `X-Subscription-Token`은 유지 |
| `gpt-search.brave.monthly-quota` | free 로컬 월 쿼터 | 기본 **2000** (`BraveSearchProperties`) |
| `NAVER_*` | 네이버 전용 | Brave 이중 키와 **무관**. 건드리지 말 것 |

베이스 키 해석: `BRAVE_API_KEY` / `gpt-search.brave.api-key` / `GPT_SEARCH_BRAVE_API_KEY` 만.  
`BRAVE_SUBSCRIPTION_TOKEN` 및 `gpt-search.brave.subscription-token` 입력은 **폐기**. HTTP 헤더 이름은 계속 `X-Subscription-Token`.  
**Free**는 `BRAVE_API_KEY_FREE` (+ 필요 시 `gpt-search.brave.api-key-free` / `GPT_SEARCH_BRAVE_API_KEY_FREE`)만 사용.

식별 힌트(대시보드 플랜으로 확정; 코드에 prefix 하드코딩 금지):  
운영자 기준 free / base 토큰은 서로 다른 Brave 플랜이다. Codex는 “어느 문자열이 free인지”를 **env 이름**으로만 구분한다.

---

## 2. 목표 동작 (명세서 / MUST)

### 2.1 검색 요청 시 Brave 내부 순서

1. **Free 키 사용 조건:** `BRAVE_API_KEY_FREE` present **그리고** 해당 월 free 사용량 **&lt; 2000** (설정 가능: `gpt-search.brave.monthly-quota`, 기본 2000).
2. Free로 HTTP 호출 (`X-Subscription-Token` = free 토큰). 성공/실패 처리·QPS·429 쿨다운은 기존 `BraveSearchService` 시임 재사용.
3. **Free → Base 전환 조건 (OR):**
   - 로컬 `monthlyRemaining` 이 0이 됨 (월 2,000 소진)
   - 프로바이더 응답 헤더로 monthly remaining ≤ 0 확인
   - Free 키가 없음
   - Free 호출이 **쿼터/플랜 한도**류로 실패 (예: 429 + quota, 명시적 plan limit). 일반 네트워크 오류는 기존 retry/쿨다운 후, **같은 free 키**로 재시도 정책을 유지하고, 쿼터 소진으로 판정될 때만 base로 승격.
4. **Base 전환 후:** 같은 Brave URL로 `BRAVE_API_KEY` 해석 결과 사용. Base 구간에서는 free `monthlyRemaining` 을 깎지 **않음**.
5. **Base도 실패**하면 그때 하이브리드 폴백: Tavily → SerpAPI → **Naver** (기존 `HybridWebSearchProvider` / 라우팅 표 유지).

### 2.2 금지 동작 (현재 버그성 동작 수정)

- Free 쿼터 소진 시 `markQuotaExhaustedAndDisable` 등으로 **Brave 프로바이더 전체를 disable** 하지 말 것.  
  → **disable free lane only**, base lane이 있으면 Brave는 계속 enabled.
- Free 소진을 Naver/Tavily 전환 신호로 쓰지 말 것.
- Free와 Base를 한 필드에 섞어 넣거나, Naver 키를 Brave 헤더에 넣지 말 것.
- 월 쿼터 카운터를 Base 호출에 적용하지 말 것 (카운터는 **free 전용**).

### 2.3 관측성

로그/트레이스 필드 (값 자체 금지):

- `brave.keyLane=free|base`
- `brave.free.remaining` (로컬)
- `brave.failoverReason=quota_exhausted|free_key_missing|provider_quota|…`
- `[AWX][api-spend]` / 기존 `ApiRoutingDebug` 패턴과 맞출 것

### 2.4 월 롤오버

기존 `resetQuotaForMonthRollover(LocalDate)` 유지. 새 달이 되면 free remaining = monthlyQuota, free lane 재활성화. Base 키 설정은 그대로.

---

## 3. 소스 수정 지시 (어디를 고칠지)

기존 시임을 **확장**할 것. 새 프로바이더 스택을 만들지 말 것.

### 3.1 필수 터치

| 파일 | 작업 |
|------|------|
| `main/java/com/example/lms/service/web/BraveSearchProperties.java` | `apiKeyFree` (또는 `api-key-free`) 필드 추가. `monthlyQuota` 기본 2000 유지. |
| `main/java/com/example/lms/service/web/BraveSearchService.java` | (1) free/base 두 credential resolve (2) `resolveActiveSubscriptionToken()` → free 우선 (3) `tryReserveFreeTierQuota` 실패/`denied` 시 **disable 전체 대신** base lane으로 승격 (4) HTTP 헤더에 active lane 토큰 설정 (5) base 호출은 quota reserve 스킵 또는 unmanaged |
| `main/java/com/acme/aicore/adapters/search/BraveSearchProvider.java` | 단일 `@Value` 키 체인에 free 추가하지 말고, `BraveSearchService` 의 lane 선택/쿼터 API를 재사용. 헤더에 provider 독자 키가 있으면 service와 **동일 정책**으로 맞출 것. |
| `main/java/ai/abandonware/nova/boot/NovaPropertyAliasEnvironmentPostProcessor.java` | `BRAVE_API_KEY_FREE` / `GPT_SEARCH_BRAVE_API_KEY_FREE` 별칭만 추가. 기존 BRAVE_* base 별칭 깨지 말 것. |
| `configs/api-routing.yaml` | `search.brave` env 목록에 `BRAVE_API_KEY_FREE` 추가. 주석으로 free→base→(then other providers). Naver 블록과 분리 유지. |
| `docs/API_ROUTING_SPEC.md` | Web search 표: Brave 행을 “Free key then Base key (same host), then Tavily…” 로 갱신. Naver는 별행 유지. env 표에 `BRAVE_API_KEY_FREE` 추가. |
| `.env.example` | `BRAVE_API_KEY_FREE=` 빈 자리 추가. 실제 값 넣지 말 것. |

### 3.2 선택 / 회귀

- `BraveOperationalGateAspect`, `BraveRateLimitState`, adaptive QPS: lane과 무관하게 Brave host QPS면 공유 가능. free disable ≠ Brave disable 인지 확인.
- 테스트: free present + remaining&gt;0 → free 헤더; remaining=0 + base present → base 헤더·Brave still enabled; both missing → 기존 missing key; Naver 설정만으로는 Brave lane 선택 안 함.
- `scripts/use_project_keys.ps1` 가 이름을 나열한다면 `BRAVE_API_KEY_FREE` 이름만 추가 (값 출력 금지).

### 3.3 하지 말 것

- `NaverSearchService` / `NAVER_*` 를 Brave 쿼터와 엮기
- 새 HTTP 클라이언트 스택 신설
- 월 2,000을 Base에도 적용
- 성공한 검증 경로를 유료 모델로 재플레이 (에이전트 spend guard 준수)

---

## 4. 구현 스케치 (비구속 힌트 — 검증 후 채택)

```text
activeToken =
  if freeKeyPresent && freeMonthlyRemaining > 0 → freeKey (lane=free, reserve quota)
  else if baseKeyPresent → baseKey (lane=base, no free quota burn)
  else → missing → disable/fail-soft as today

on free provider-quota signal → set freeRemaining=0; retry once with base (same request) if base present
on base failure → hybrid fallback (Tavily/SerpAPI/Naver) — NOT “try Naver as brave free”
```

가설일 뿐이며, 기존 `QuotaReservation` / `completeFreeTierQuota` / `releaseFreeTierQuota` 의미를 깨지 말고 맞출 것.

---

## 5. 완료 기준 (Definition of Done)

1. Free 키만 있고 remaining&gt;0 → 요청이 free 토큰 사용, remaining 감소.
2. Free remaining 0 + Base 키 있음 → **Brave 유지**, base 토큰 사용, Naver로 즉시 점프하지 않음.
3. Free remaining 0 + Base 없음 → 그때 기존 다음 프로바이더(Tavily/…/Naver).
4. 로그에 `keyLane` 구분, 시크릿 평문 없음.
5. `configs/api-routing.yaml` + `docs/API_ROUTING_SPEC.md` + `.env.example` 동기화.
6. 관련 단위 테스트 통과 (최소: lane 선택 + 쿼터 소진 시 disable-all 회귀 없음).

---

## 6. AGENTS.md / 전역 지침 한 줄 (추가용)

Codex/에이전트 전역에 아래를 `AGENTS.md` 적절한 API/routing 절 또는 Core Request 근처에 **짧게** 추가:

> **Brave dual-key:** Use `BRAVE_API_KEY_FREE` first up to `gpt-search.brave.monthly-quota` (default 2000/month); then same-host Brave **base** via `BRAVE_API_KEY`. `BRAVE_SUBSCRIPTION_TOKEN` is retired. Keep header `X-Subscription-Token`. Do not treat Naver as Brave free/base. Spec: `docs/codex/BRAVE_FREE_TO_BASE_ROUTING_DIRECTIVE.md`.

전체 규칙은 본 문서가 SSOT.

---

## 7. 명세서 요약 (Contract)

**Provider:** Brave Search Web API  
**Endpoint:** `https://api.search.brave.com/res/v1/web/search`  
**Auth header:** `X-Subscription-Token: <activeLaneToken>`  

**Lanes:**

| Lane | Env | Monthly local cap | On cap |
|------|-----|-------------------|--------|
| free | `BRAVE_API_KEY_FREE` | 2000 (default) | promote to base lane |
| base | `BRAVE_API_KEY` | none (this feature) | fail → non-Brave fallbacks |

**Non-goals:** Naver 통합, Brave 요금제 자동 구매, 키 문자열 저장소 외 복제.

**Compatibility:** Prefer existing env APIs; free→cheap→paid 정책에서 Brave free는 free_local, Brave base는 동일 벤더의 유료 연장, Naver는 별도 free-ish 검색 프로바이더.
