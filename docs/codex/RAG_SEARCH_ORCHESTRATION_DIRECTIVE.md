# Devin 지시서: RAG 검색 API 오케스트레이션 정리 (Brave dual-key + 하이브리드 폴백)

**대상 루트:** `C:\AbandonWare\demo-1\demo-1\src`
**작성일:** 2026-09-19
**작성자:** Cline (검토용)
**목적:** RAG 웹 검색 경로를 "Brave free → Brave base → (Tavily → SerpAPI) → Naver" 순서로 정합화한다. 검색 **프로바이더 조합(orchestration)** 위주로 수정하며, 개별 프로바이더의 내부 HTTP/파싱 로직은 건드리지 않는다.

관련 SSOT:
- `docs/codex/BRAVE_FREE_TO_BASE_ROUTING_DIRECTIVE.md` — Brave free/base 이중 키 계약
- `configs/api-routing.yaml` — 머신 리더블 라우팅 표 (`routes.search` 절)
- `docs/API_ROUTING_SPEC.md` — 라우팅 스펙 문서
- `AGENTS.md` `DEMO1-BRAVE-DUAL-KEY` 절

---

## 0. 착각 금지 (필독)

| 구분 | 무엇인가 | 무엇 **아닌가** |
|------|----------|-----------------|
| **Brave Free** | `BRAVE_API_KEY_FREE`. 월 로컬 쿼터 2,000 (기본) | Naver 아님, 별도 벤더 아님 |
| **Brave Base** | `BRAVE_API_KEY`. 같은 `api.search.brave.com` 호스트. `BRAVE_SUBSCRIPTION_TOKEN`은 폐기 | "베이스" ≠ Naver |
| **하이브리드 폴백 체인** | Brave(free→base) 모두 실패 시 Tavily → SerpAPI → Naver | free 소진 시 바로 Naver로 점프 **금지** |
| **Tavily / SerpAPI** | 저비용 유료 폴백 (`TAVILY_API_KEY`, `SERPAPI_API_KEY`) | Brave lane이 아님 |

**한 줄 요약:** free/base는 같은 Brave 프로바이더의 lane이고, Naver·Tavily·SerpAPI는 그 다음 런그의 별도 프로바이더다. 토큰 문자열 하드코딩 금지 — 코드는 env **이름**만 본다.


---

## 1. 현재 상태 요약 (2026-09-19 스냅샷 기준)

| 항목 | 상태 | 근거 |
|------|------|------|
| `BraveSearchService` (2070 LOC) | 존재. 월 쿼터 카운터(`monthlyRemaining`), `QuotaReservation`, 429 쿨다운, QPS 레이트리밋 내장 | `main/java/com/example/lms/service/web/BraveSearchService.java` |
| `BraveSearchProperties` | `gpt-search.brave.*` 레코드: `enabled`, `baseUrl`, `apiKey`, `qpsLimit`, `monthlyQuota`(기본 2000), 쿨다운 | 동 패키지 |
| `HybridWebSearchProvider` (4110 LOC) | Brave/Naver 병합 오케스트레이션. `gpt-search.hybrid.primary=BRAVE`(기본), `skip-naver-if-brave-sufficient`, 타임아웃/버짓 가드 다수 | `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java` |
| `api-routing.yaml routes.search` | brave(free_local), tavily, serpapi, naver 4행 존재. **brave 행 env에 `BRAVE_API_KEY_FREE` 아직 없음** | `configs/api-routing.yaml:60-77` |
| `WebSearchRetriever` (rag) | Naver/Brave 2-provider 팬아웃 제한 패치 적용됨 | `main/java/com/example/lms/service/rag/WebSearchRetriever.java` L420 |
| Tavily/SerpAPI 프로바이더 | 존재하나 **HybridWebSearchProvider 내부가 아님** — `SerpApiProvider` + `TavilyWebSearchRetriever`(main/java에 동명 3종; yaml seam은 `com.abandonware.ai.agent.integrations`) + `CachedWebSearch.searchMulti` 팬아웃. 스킵·rate-limit 트레이스는 `WebFailSoftSearchAspect`/`HybridWebSearchEmptyFallbackAspect` | `main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java`, `main/java/ai/abandonware/nova/orch/aop/` |
| `api-routing.yaml` 미러 | `main/resources/configs/api-routing.yaml` 에 동일 `routes.search` 절 존재 — 변경 시 **두 파일 동기화** 필요 | `main/resources/configs/api-routing.yaml:60-77` |

**결론:** Brave lane 분리는 스펙만 있고 라우팅 표/별칭 미동기화. Devin 작업은 **조합(라우팅/폴백/설정)** 정리에 집중하고, Brave 내부 쿼터 메커니즘은 이미 존재하는 것을 재사용한다.

---

## 2. 목표 동작 (명세서 / MUST)

### 2.1 Brave 내부 lane 순서 (BRAVE_FREE_TO_BASE_ROUTING_DIRECTIVE §2.1 재확인)

1. `BRAVE_API_KEY_FREE` present **그리고** 로컬 `monthlyRemaining > 0` → free 토큰으로 호출, 쿼터 reserve/complete.
2. 전환 조건(OR): remaining==0 / 프로바이더 헤더 quota 소진 / free 키 없음 / free 호출이 quota·plan-limit류 실패(429+quota). 일반 네트워크 오류는 **같은 free 키로** 기존 재시도 유지.
3. base 전환 후: 같은 URL에 `BRAVE_API_KEY`. **free 쿼터 카운터는 깎지 않음.** 헤더는 `X-Subscription-Token`.
4. base도 실패 → 하이브리드 폴백 런그로 진행.

### 2.2 하이브리드 폴백 런그

```
[Brave free] →(quota/키 없음)→ [Brave base] →(실패)→ [Tavily] → [SerpAPI] → [Naver]
```

- 각 런그는 **fail-soft**: disabled/missing-key/timeout/rate-limit/after-filter starvation을 서로 구분해 트레이스에 남긴다.
- free 소진을 Naver 전환 신호로 쓰지 않는다. Naver는 **Brave 두 lane이 모두 불가할 때만**.
- `gpt-search.hybrid.primary=BRAVE` 기본 유지. primary 변경이 필요하면 설정으로만.

### 2.3 관측성 (필수 트레이스 필드, 값 평문 금지)

- `brave.keyLane=free|base`, `brave.free.remaining`, `brave.failoverReason=quota_exhausted|free_key_missing|provider_quota|…`
- `hybrid.web.provider.<id>.skipped` + `.skipReason` (기존 패턴 준수 — 예: `missing_brave_api_key`)
- `configs/api-routing.yaml`의 `debug.fields` 목록과 정합

### 2.4 금지 동작

- free 쿼터 소진 시 `BraveSearchService` **전체 disable** 금지 → free lane만 disable.
- 월 2,000 카운터를 base 호출에 적용 금지.
- Naver 키를 Brave 헤더에, 혹은 반대로 넣기 금지.
- 새 HTTP 클라이언트 스택 신설 금지. `BraveSearchService`의 기존 RestTemplate/QPS/쿨다운 재사용.
- `NaverSearchService`/`NAVER_*` 를 Brave 쿼터 로직과 결합 금지.


---

## 3. 수정 대상 파일 (우선순위 순)

### 3.1 필수

| 파일 | 변경 내용 |
|------|-----------|
| `configs/api-routing.yaml` | `routes.search.brave.env` = `[BRAVE_API_KEY_FREE, BRAVE_API_KEY]`. `BRAVE_SUBSCRIPTION_TOKEN` 제거. Naver 행 분리 유지. `main/resources/configs/api-routing.yaml` 미러에도 동일. |
| `main/java/com/example/lms/service/web/BraveSearchProperties.java` | free 키 전용 바인딩 추가 (예: `api-key-free`). 기존 필드 이름/기본값 **불변**. |
| `main/java/ai/abandonware/nova/boot/NovaPropertyAliasEnvironmentPostProcessor.java` | `BRAVE_API_KEY_FREE` / `GPT_SEARCH_BRAVE_API_KEY_FREE` 별칭만 추가. 기존 `BRAVE_*` base 별칭 깨지 말 것. |
| `main/java/com/example/lms/service/web/BraveSearchService.java` | 키 해석/헤더 주입 지점에 lane 선택 삽입: (1) free present + `tryReserveFreeTierQuota` 성공 → free 토큰 (2) 그 외 base 토큰 (3) base 호출은 quota reserve 스킵. 실패 시 `disabledReason`에 lane 포함. **기존 QuotaReservation 의미 유지.** |
| `docs/API_ROUTING_SPEC.md` | Web search 표 Brave 행: "Free key (monthly 2000) then Base key (same host), then Tavily → SerpAPI → Naver". env 표에 `BRAVE_API_KEY_FREE` 추가. |
| `.env.example` | `BRAVE_API_KEY_FREE=` 빈 자리 추가 (실제 값 금지). |

### 3.2 회귀 확인 (수정 여부는 검증 후 결정)

| 파일 | 확인 항목 |
|------|-----------|
| `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java` | Brave 실패 시 Tavily/SerpAPI 런그를 거치지 않고 Naver로 바로 점프하는 경로가 남아 있는지 추적. 남아 있으면 런그 순서로 교체. `gpt-search.hybrid.*` 프로퍼티 이름 유지. |
| `main/java/com/acme/aicore/adapters/search/BraveSearchProvider.java` | 어댑터가 자체 키 해석을 하고 있다면 `BraveSearchService`의 lane 선택 API에 위임. 독자 `@Value` 키 체인에 free를 끼워 넣지 말 것. |
| `main/java/com/example/lms/service/rag/WebSearchRetriever.java` | 2-provider 팬아웃(Naver, Brave) 패치가 새 런그 순서와 충돌하는지 확인. 충돌 시 팬아웃 상한은 유지하되 대상 목록을 라우팅 표 기준으로 정렬. |
| `ai/abandonware/nova/orch/web/brave/BraveAdaptiveQps*` | QPS 인터셉터는 host 단위 — lane 무관 공유 가능 확인. free disable ≠ Brave disable 회귀 테스트. |
| `main/java/com/example/lms/probe/ProbeConfig.java` | `envPresent.BRAVE_API_KEY_FREE` 관측 필드 추가 (값이 아니라 presence만). |

### 3.3 테스트

| 테스트 | 내용 |
|--------|------|
| 신규: `BraveDualKeyLaneSelectionTest` | (a) free present + remaining>0 → free 헤더 (b) remaining==0 + base present → base 헤더 + Brave still enabled (c) 둘 다 없음 → 기존 missing-key (d) Naver 설정만 있을 때 Brave lane 선택 안 함 |
| 회귀: `HybridRetrieverImplicitConsistencyTest` | 기존 `web.brave.skipped.reason=missing_brave_api_key` 케이스 유지 |
| 회귀: `SearchProviderTraceStandardizationTest` | 트레이스 필드 redaction 유지 |

실행: `.\gradlew.bat test --tests <Fqcn>` (AGENTS.md DEMO1-TOOLCHAIN-AUTO-SELECT).


---

## 4. 하지 말 것 (Non-goals)

- Naver/Tavily/SerpAPI 내부 HTTP·파싱 로직 변경
- 새 검색 프로바이더 추가, 라우팅 표 이외의 폴백 런그 신설
- Spring Boot/LangChain4j 버전 변경 (LangChain4j는 `1.0.1` 고정)
- `openssl`/`opnessl` 키 이름·값·형식 변경 (금지)
- 토큰 평문 로깅/테스트 하드코딩
- Meta Display lens 출력 포맷 변경 (본 작업 범위 밖)

---

## 5. 구현 스케치 (비구속 — 검증 후 채택)

```text
activeToken =
  if freeKeyPresent && freeMonthlyRemaining > 0 → freeKey (lane=free, reserve quota)
  else if baseKeyPresent → baseKey (lane=base, no free quota burn)
  else → missing → 기존 fail-soft

on free provider-quota signal → freeRemaining=0; base present 시 같은 요청 1회 base 재시도
on base failure → hybrid fallback rung (Tavily → SerpAPI → Naver)
```

기존 `QuotaReservation` / `completeFreeTierQuota` / `releaseFreeTierQuota` 의미를 깨지 말고 맞출 것.

---

## 6. 완료 기준 (Definition of Done)

1. Free 키만 있고 remaining>0 → free 토큰 사용 + remaining 감소.
2. remaining==0 + base 키 있음 → **Brave 유지**, base 토큰 사용, Naver 즉시 점프 없음.
3. remaining==0 + base 없음 → 그때 Tavily → SerpAPI → Naver 런그.
4. 트레이스에 `keyLane` 구분, 시크릿 평문 없음.
5. `configs/api-routing.yaml` (+ `main/resources/configs/api-routing.yaml` 미러) + `docs/API_ROUTING_SPEC.md` + `.env.example` 동기화.
6. 신규 lane 선택 테스트 + 기존 hybrid/redaction 테스트 통과.
7. 변경 후 `.\gradlew.bat :compileJava -x test` + `:processResources` 통과. 라이브 반영은 DevWatch `[DEV-RELOAD] socket ready` 확인 (AGENTS.md DEMO1-SPRING-VIBE-RELOAD).

---

## 7. 참조 경로 색인 (Devin 빠른 진입용)

- Brave 서비스: `main/java/com/example/lms/service/web/BraveSearchService.java` (생성자 L228, 쿼터 카운터 L241, `@Value` base-url L141)
- Brave 프로퍼티: `main/java/com/example/lms/service/web/BraveSearchProperties.java`
- 하이브리드 오케스트레이션: `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java` (`search` L306, brave-first L442)
- RAG 웹 리트리버: `main/java/com/example/lms/service/rag/WebSearchRetriever.java` (팬아웃 패치 L420)
- RAG 하이브리드 리트리버: `main/java/com/example/lms/service/rag/HybridRetriever.java`
- 어댑터: `main/java/com/acme/aicore/adapters/search/BraveSearchProvider.java`
- 별칭 부트: `main/java/ai/abandonware/nova/boot/NovaPropertyAliasEnvironmentPostProcessor.java`
- 라우팅 표: `configs/api-routing.yaml` (search 절 L59-77) + 미러 `main/resources/configs/api-routing.yaml`
- SerpAPI 프로바이더: `main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java`
- Tavily 리트리버: `main/java/com/abandonware/ai/agent/integrations/TavilyWebSearchRetriever.java` (yaml seam; 동명 클래스가 `com.abandonware.ai.integrations`, `com.example.lms.service.rag` 에도 존재 — live seam 확인 필수)
- 멀티 프로바이더 팬아웃: `main/java/com/acme/aicore/adapters/search/CachedWebSearch.java` (`searchMulti` — `WebSearchRetriever` L422 호출 지점)
- 폴백/스킵 트레이스: `main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java`, `main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java`
- 스펙: `docs/API_ROUTING_SPEC.md`, `docs/codex/BRAVE_FREE_TO_BASE_ROUTING_DIRECTIVE.md`

**주의:** 런타임 소유권 — 본 체크아웃은 다중 에이전트 공유. 소스 쓰기 전 `__patch_drop__/source_edit_session.ps1 -Action begin -TargetManifest` (target-scoped lease) 필수. 검증은 집중 테스트 → 라이브 재기동(DevWatch) 순.

