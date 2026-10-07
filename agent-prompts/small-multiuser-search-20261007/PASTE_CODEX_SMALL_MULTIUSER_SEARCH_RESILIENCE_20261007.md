[DOT-BRIEF]
ProjectRoot: C:\AbandonWare\demo-1\demo-1\src
Goal: 이미 정상이라고 사용자가 확인한 첫 질문·즉시 후속 질문의 검색과 답변을 보존하면서, 2~5명의 작은 burst에서 검색 대기·제공자 제한·취소·장애회복을 기존 seam의 최소 수정으로 개선한다.
Contract: SMALL_MULTIUSER_SEARCH_RESILIENCE_20261007 / DRAFT / DOCUMENT_ONLY
작성 기준: 2026-10-07 UTC. 아래 WP는 후속 구현 지시이며 이번에는 실행하지 않았다. 제품 patch·설정 변경·restart·실검색·OAuth/모델 호출·유료 호출·commit/push=0.
외부 API: 공식 공개 웹 문서 읽기만. 키/토큰/개인 인증 파일 읽기 및 유료 API 호출 0.
PLUGIN_USAGE:
- UAW: USED(P0-first 읽기 조사·근거 상태 분리)
- Codex collaboration: USED(제공자 계약/검색 wire/요청 격리의 독립 읽기 조사)
- Web: USED(Brave/Naver/Gemini 공식 계약 확인)
- Superpowers: USED(using-superpowers의 delegated-task 예외 확인)
- Browser: NOT_RUN(제품 브라우저·실접속 검증 없음)
- Computer, GitHub, Vercel, Sites, Supabase: NOT_USED
- GLM=NOT_USED

## Goal — 먼저 할 두 가지

1. P0: Brave의 **로컬 permit 대기 실패**를 실제 wire 429·공유 credential quota·제공자 장애와 분리한다. 로컬 혼잡 하나가 다른 사용자의 provider-down 판정으로 번지지 않게 한다.
2. P0: 현재 **Naver async wire와 Brave QPS gate** 앞에 짧고 bounded한 제공자 대기를 기존 executor/permit seam에 연결한다. 사용자별 공정성·취소·deadline을 먼저 증명한다. Redis/DB나 새 전역 오케스트레이터가 필요하다는 근거는 없다.

OAuth 주 경로와 기존 사용자의 모델 선택·strict/capability 설정을 보존한다. 로컬 Ollama 병목은 이번 기본 경로의 원인으로 가정하지 않는다. Gemini 표시 기능은 P2, Brave 유료 혼잡 완화는 계약·승인 확인 전 옵션이다.

## Facts — 확인 / 추정 / NOT_RUN

- 확인(사용자 최신 직접 보고): 첫 번째와 두 번째 검색 및 결과 표시가 잘된다. 과거 실패 지시서로 이를 다시 실패 상태로 되돌리지 않는다. 이번 작성자는 현재 실행본의 첫 2턴을 별도로 호출하지 않았다.
- 확인(소스): root `build.gradle.kts:775-794`는 `main/java`, `main/resources`, `src/test/java`를 사용한다. 실제 settings는 `settings.gradle:15,25`; `settings.gradle.kts:4-8`는 sentinel이다. `app/build.gradle.kts:44-57`은 빈 모듈. 참조/백업 트리에서 코드를 이식하지 않는다. root 선언 LangChain4j는 `build.gradle.kts:134-135,769-770`의 1.0.1; resolved graph 검사는 NOT_RUN.
- 확인(과거 보고): `data/agent-handoff/codex-autonomy/search-answer-recovery-20261006-9618b765/SEARCH_ADMISSION_RECOVERY_REPORT_20261006.md`는 admission75/75 및 관련209/209, 브라우저13/15를 각각 기록한다. 현재 소스의 새 PASS나 첫2턴 품질 PASS로 재사용하지 않는다. 이미 고친 projected admission을 되돌리지 않는다.
- 추정: 단일 JVM·2~5 사용자 규모라면 기존 in-process queue/계측으로 충분할 가능성이 있다. replica 수·현재 traffic·실효 설정·실제 plan은 NOT_OBSERVED. 운영 처리량/지연 보장으로 표현하지 않는다.
- NOT_RUN: 이번 mock/Gradle/build/live/브라우저/부하 검증 전부. 아래 Acceptance는 실행 예정이다. 공개 웹 읽기 성공은 provider wire 성공이 아니다.

기존 지시서·스킬 문서는 참조만: Downloads의 MULTIUSER_RESILIENCE_20261005(fd2bf215dec6), SEARCH_ADMISSION_AND_ANSWER_RECOVERY_20261006(8aba2f7f9498), SESSION469_FOLLOWUP_SEARCH_CANCEL_RECOVERY_20261007(f265be93f1d2), SEARCH_RECOVERY_SKILL_REPORT_20261007(a71083255d49), SKILL_FIRST_TWO_TURN_EVIDENCE_REPORT_20261007(b848f9daf19d). 앞의 세 지시서 파일명 접두사는 `PASTE_CODEX_`다. 진단 스킬의 개선·과거 report count를 제품 완료로 간주하지 않는다.

## 현재 entry → response 흐름과 재사용 범위

아래 경로는 모두 ProjectRoot 상대경로다. 정적 연결이며 실제 선택 property/runtime 배선은 실행 시 확인한다.

| 경계 | 파일:줄 근거 / 재사용할 계약 |
|---|---|
| entry·owner admission | `main/java/com/example/lms/api/PublicChatAdmissionGuard.java:58-82` process-local 전체/owner 동시 admission; `:146-177` CAS close-once. fair semaphore의 즉시 tryAcquire는 provider 검색 대기열이 아니다. |
| owner/session/run·async entry | `main/java/com/example/lms/api/ChatApiController.java:1419-1455` sync entry/owner/session/admission; `:1531-1542,1554-1586,1591,1625` blocking callable/boundedElastic/finally; `:1723-1741` stream admission/beginOrJoin/owner-bound prior evidence; `:1818-1829,2088-2093,3139-3149` worker context/cancellation/release. `main/java/com/example/lms/api/ChatGenerationAdmissionFilter.java:47-69,101-145` owner/IP rolling turn/replay fingerprint와 registry의 run owner를 재사용한다. |
| accepted-run deadline 특례 | `main/java/com/example/lms/service/chat/ChatRunExecutionContext.java:140-166,304` accepted run은 ingress 잔여 예산을 재강제하지 않고 configured finite wait를 사용한다. controller `:1797-1798,1825-1829`도 accepted run의 ingress budget을 분리한다. owner lease TTL을 검색 deadline으로 쓰지 않는다. |
| 검색 scope/executor | `main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java:227-238` conditional Primary retriever; `main/java/com/example/lms/config/SearchExecutorConfig.java:161-205` bounded ArrayBlockingQueue/AbortPolicy/context wrapper; `main/java/ai/abandonware/nova/orch/adapters/NovaAnalyzeWebSearchRetriever.java:476-503,573-648` deadline-aware fair permit/CAS lease. |
| logical vs wire 예산 | `main/java/com/example/lms/service/rag/SelfAskSearchBudget.java:61-99` 요청별 논리 query와 HTTP attempt를 따로 센다. 현재 코드 ceiling은 provider 계약값이 아니다. Brave `:1260-1267`, Naver `:2504-2510`의 wire 직전 예약을 우회하지 않는다. |
| provider/fallback | `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java:378-450` Brave→Naver, TRUE_ZERO 때만 Gemini expand once→두 번째 검색 cycle. timeout/error/disabled를 executed_empty로 바꾸지 않는다. `:637-639,674`는 Brave cache-only→searchWithMeta, Naver searchWithTraceSync다. |
| Naver wire | `main/java/com/example/lms/service/NaverSearchService.java:1466,1546,2149-2161,1112-1116,2381-2526` sync facade가 Mono/cache loader/async wire로 이어진다. `:2495-2526`의 rate delay는 최대200ms 뒤 wire를 시작한다. 별도 sync 구현 `:3254`의 semaphore.acquire가 이 async 경로를 보호하지 않는다. 공유 pool queue와 provider 제한은 다르다. |
| Brave wire·공유 상태 | `main/java/com/example/lms/service/web/BraveSearchService.java:1164-1172` singleton Guava QPS gate/deadline wait. `:1178-1186` local 실패→startCooldown. `main/java/ai/abandonware/nova/orch/aop/ProviderRateLimitBackoffAspect.java:175-187`→`main/java/ai/abandonware/nova/orch/web/RateLimitBackoffCoordinator.java:52,62,323`의 provider-name 공유 상태. short local pacing은 이미 실제429 streak와 구별되지만 다른 요청의 skip에 영향을 줄 수 있다. |
| terminal·breaker | `main/java/com/example/lms/search/provider/HybridSearchExecution.java:95-110,153-175` close 후 시작/trace 차단. `ProviderRateLimitBackoffAspect.java:454-478` CANCELLED_NO_BREAKER는 이미 있다. `main/java/com/example/lms/infra/resilience/NightmareBreaker.java:144-153,587-652` Clock/CallPermit terminal CAS 및 cancel/abandon 반환을 보존한다. Future.cancel(false)는 실제 I/O 종료 증거가 아니다. |
| prompt·response | `main/java/com/example/lms/service/ChatWorkflow.java:3173-3193` 현재 질문/이력/web/RAG→PromptContext, `:3520,14147` PromptBuilder.build(ctx); `:4973` final generation/auxiliary rescue 결합; `:10230-10244` exact run context 우선 cancel 확인. controller `:2529-2535,4681-4682,4702` owner-bound 결과/취소 저장 fence. ad hoc prompt concatenation 금지. |

**공유 범위:** owner/global admission·search executor·Brave QPS/cooldown·Naver cache/rate policy·breaker는 프로세스 공유 상태다. owner/session/run/cancellation/논리 query/HTTP attempt/late-result acceptance는 요청 scope다. ThreadLocal context는 기존 wrapper/Mono subscriber capture로 전달해야 하며 작업 종료 후 정리한다. 공유 provider health와 다른 사용자의 private trace/history를 합치지 않는다.

캐시/singleflight: Brave `:716` @Cacheable(sync=true)의 query/topK 키는 bounded route cold miss `Hybrid:637-639`를 감싸지 않는다. Naver `:4668-4689` 키는 policy/filter/allowlist/topK/query, `:4725-4727` child future는 A waiter cancel→shared loader cancel 전파를 막는다. public eligibility/tenant 범위는 이 키만으로 입증되지 않는다. 유출이 확인됐다는 뜻은 아니다. 최초 loader A의 짧은 예산이 B에 영향을 주는지는 offline 재현한다. **같은 키라고 private query/history/tenant 결과를 공유하지 않는다.** 기존 공용 결과 재사용 범위만 허용하고 새 영속 저장/retention 확대는 제외한다.

## 제공자 계약 — 공식 공개 문서, 확인일 2026-10-07

| 제공자 | 확인 / 적용할 경계 |
|---|---|
| Brave | [Rate limiting](https://api-dashboard.search.brave.com/documentation/guides/rate-limiting): plan별 1초 sliding window, HTTP429, X-RateLimit-Limit/Policy/Remaining/Reset; Reset은 각 window의 남은 초. concurrency와 arrival RPS는 별개. 예시 quota를 상수로 복사하지 않는다. Retry-After 보장은 확인 못 했으므로 있으면 파싱하고 없으면 공식 reset/기존 capped backoff를 사용한다. |
| Brave plan/key | [가격](https://api-dashboard.search.brave.com/documentation/pricing)의 현재 Search 공개 수치는 $5/1,000 requests·월 $5 credit·50 RPS이며 실제 계정 entitlement가 아니다. [FAQ](https://api-dashboard.search.brave.com/documentation/resources/help-feedback)는 활성 plan별 key, 기존 postpaid 보존, 새 prepaid 활성 시 새 key를 설명한다. FREE/BASE alias만으로 별도 plan/독립 quota를 증명하지 않는다. |
| Naver | [웹 검색](https://developers.naver.com/docs/serviceapi/search/web/web.md)은 client ID별 하루25,000회 합산. [공통 오류](https://developers.naver.com/docs/common/openapiguide/errorcode.md)는 일일/초당 초과 모두429. 초당 숫자와 Retry-After/limit-header 계약은 미확인. 모든429를 짧은 transient로 재시도하지 않는다. |
| Gemini | [Rate limits](https://ai.google.dev/gemini-api/docs/rate-limits): key가 아닌 project별 RPM/input TPM/RPD, 모델/tier별 차이, 실제 active limit은 AI Studio. [회복 지침](https://ai.google.dev/gemini-api/docs/troubleshooting): transient backoff+jitter/상한, SDK 자동 retry와 상위 retry 중복 주의. Retry-After 보장은 미확인. 이 계약은 main ChatGPT OAuth의 한도 증거가 아니며 OAuth도 무제한으로 가정하지 않는다. |

저장소 `docs/provider-limits/brave-search-limits.md`, `docs/provider-limits/naver-search-limits.md`, `docs/provider-limits/gemini-limits.md`의 accountPlan/accountExactLimits는 unknown이다. 공개 한도·local 보호상수·actual credential/project/plan을 분리한다. 키/토큰 파일을 읽거나 사용자에게 billing 로그인·플랜 퀴즈를 요구하지 않는다.

**Free→Paid는 모델 스위치가 아니다.** 기존 dual-key 정책(`docs/agents-rules/DEMO1-BRAVE-DUAL-KEY.md`)은 보존하되, 이번 최신 지시는 paid 실행/가입/키추가/결제를 금지한다. congestion 기반 새 유료 승격은 HOLD: 마스킹된 `routeEnabled/planEvidenceStatus/quotaScopeId/approvedBudget/maxWireCalls`가 실제 authorized 별도 route를 증명하고 사용자가 비용 상한을 승인한 뒤 optional feature flag로만 검토한다. 동일 plan의 여러 key는 quota 증가가 아니다. 일반429를 quota 소진으로 보고 base에 보내거나 무료한도 우회용 계정/key 회전을 만들지 않는다.

## COMMON_RULES — 세 가지 예산을 혼동하지 말 것

- 동시 요청/실행 HTTP 수: owner/global/provider permit. 초당/RPM: shared credential의 arrival pacing. 일일/월 quota: 별도 window·reset. 하나의 semaphore로 셋을 대체하지 않는다.
- logical query 하나가 cache hit·Brave/Naver·rewrite·retry·fallback으로 여러 wire를 만들 수 있다. queued/cache hit는 실제 outbound로 세지 않고, **모든 실제 retry/fallback wire는 요청 HTTP 예산과 해당 shared provider budget 둘 다** 반영한다. existing cache/quota accounting 정책은 임의 변경하지 않는다.
- 사용자/세션 budget과 provider의 plan/client ID/project budget은 독립 축이다. 여러 인증키의 quotaScope가 미확인일 때 독립 허용량을 만들지 않는다. 단일 replica 인프로세스 가정은 명시하고, multi-replica가 확인되면 그 공유 limiter 설계만 별도 HOLD한다.

## Work — 작은 단계, 단계당 원인 하나

Primary skill: `$demo1-devin-directive-loop` DRAFT(router 결과). UAW는 조사 절차이며 새 실행 엔진이 아니다. 구현 단계 진입 시 `python -B scripts/demo1_vibe_skill_router.py resolve "소규모 다중사용자 검색 제한 회복"`로 한 primary route를 다시 확인한다. patch 범위를 스킬 정리로 확대하지 않는다.

### WP0 — 보존·배선·deadline 계약 고정

현 AGENTS/PROJECT_STATUS, actual settings/sourceSets, source lease/preimage/hash, 실제 selected caller를 확인한다. 기존 첫2턴 admission/근거전달/취소 복구 패치는 KEEP. 관련 파일 변경은 목표 scoped lease와 checkpoint 뒤 한 writer만 수행한다. 겹친 live lease는 기존 lease-wait 절차로 해당 파일만 WAIT; 강제 해제/우회 구현 금지.

**검색 deadline 공백을 먼저 결정한다:** sync ingress는 기존 remaining budget을 따른다. accepted stream은 기존 ingress 분리 정책을 보존하면서, 기존 run/search context에 검색 시작 시 한 번 정한 finite absolute deadline을 결속할 수 있는지 RED로 검증한다. queueWait + rateWait + wire + retryBackoff + fallback은 같은 잔여시간을 소비하고 generation/response reserve를 보존한다. 시도마다 deadline을 다시 만들거나 ownerLease TTL을 대신 쓰지 않는다. 현재 finite transport wait와 합성해 대기열만 짧게 제한한다. 이를 기존 context에 안전하게 연결 못 하면 해당 deadline WP만 HOLD; accepted run 전체 TTL을 무단 도입하지 않는다.

### WP1 — 로컬 혼잡을 공유 장애로 승격하지 않기 (P0, 먼저)

범위: BraveSearchService `:1164-1190` + ProviderRateLimitBackoffAspect `:175-187` 및 기존 `ProviderRateLimitBackoffAspectTest`; coordinator는 실제 RED가 가리킬 때만. A의 local acquire timeout/queue full 후 B가 공유 자원 여유와 정상 provider에서 진행하는 RED를 만든다.

queue-full/queue-expired/owner-cancel/deadline-exhausted는 요청별 이유로 돌려준다. provider shared pacing·실제 quota 보호는 계속 지킨다. wire429/503/실I/O 장애의 cooldown은 보존한다. local실패를 전체 provider-down/장기 breaker-open으로 표시하는 연결만 최소 수정하며, 동일 요청의 tight retry는 기존 attempt bound로 막는다. 취소가 이미 NO_BREAKER라는 계약은 재구현하지 않는다.

### WP2 — 기존 provider seam의 짧은 bounded fair wait (P0)

범위: NaverSearchService async wire `:2381-2526`를 먼저; SearchExecutorConfig `:161-205`와 Nova permit `:476-503,573-648`는 재사용 기준. 필요한 경우만 Brave `:1164-1172,1260-1267`에 동일 계약을 연결한다. 독립 provider seam은 순서대로 닫고 동시에 넓게 수정하지 않는다.

제공자/authorized quotaScope마다 작은 bounded 대기/active permit을 두고, 기존 owner hash의 FIFO 또는 owner round-robin+owner cap으로 A의 연속 요청 뒤 B가 굶지 않음을 증명한다. queue capacity·queueWait cap·wire concurrency·RPS·quota 값은 서로 다른 설정/관측값이다. actual 한도를 지어내지 않고 기존 property 이름/효과를 보존한다. 우선 synthetic 2/3/5 owner에서 필요한 최소값을 평가한다. 전체 검색을 global serial로 만들지 않는다. 필요 내부 구조는 기존 admission/executor 소유 seam 안의 최소 확장만; 새 public scheduler/service/wrapper 체인 금지.

queue full은 즉시 명확한 local reason+기존 fallback. queue wait는 finite min(queue cap, search remaining-reserve); 요청 deadline이 끝나면 outbound0. queue에 무한 parked worker/thread를 두지 않는다. Reactor event loop에서는 semaphore.acquire/Thread.sleep 금지, 기존 scheduler/executor를 재사용한다. queued cancel은 제거+예약 반환, running cancel은 소비자 terminal과 late acceptance부터 닫고 **실제 worker 종료 전 wire permit을 돌려 과잉 실행하지 않는다**. atomic acquire/close-once, release finally를 success/error/timeout/reject/cancel에 걸쳐 검증한다.

### WP3 — 429·fallback·half-open·공용 singleflight 연결 검증 (P0/P1)

범위: HybridWebSearchProvider `:378-450`, HybridSearchExecution `:95-175`, 기존 provider wire/coordinator/브레이커 테스트. 앞 단계 변경 때문에 재현된 한 seam만 수정한다.

429는 rate window vs exhausted quota를 구분한다. Retry-After가 있으면 delta/date를 파싱하고 공식 reset과 결합; 없거나 잘못된 값은 기존 capped exponential backoff+jitter. deadline 안에서만, 한 layer가 retry를 소유하고 기존 attempt/wire 상한을 늘리지 않는다. jitter 때문에 서버 지정 최소 wait보다 빨리 retry하지 않는다. SDK/transport/AOP/fallback의 중첩 retry multiplier를 테스트한다. quota/day exhausted·401/403·disabled는 무의미한 retry 없이 기존 정직한 fallback/상태. breaker half-open은 제한된 probe만, 동시 사용자 전원이 재시작하는 herd 금지.

Brave와 Naver가 모두 실패해도 기존 bounded 순서·TRUE_ZERO 정의·질문 제약을 유지한다. fallback은 잔여 deadline/shared credential budget 안에서만 호출하고 cancel 뒤 새 fallback0. 실패를 빈 정상 결과·가짜 근거·무조건 HOLD로 재포장하지 않는다. 기존 fail-soft 공개/메모리 금지 계약을 보존한다.

singleflight는 **공용 검색 결과로 허용된 동일 key만**: cache-key 정책/언어/필터/topK 차이를 유지한다. 공용 cold miss에서 중복 wire를 줄이되 A consumer cancel이 B waiter를 취소하지 않고, 최초 A의 deadline 때문에 B가 오염/차단되지 않는지 확인한다. private query/history/tenant는 공유 제외. eligibility/계약이 증명되지 않으면 이 최적화만 HOLD하고 P0는 진행한다.

### WP4 — 결과 인계; P2는 후속

changed files/pre-post hashes, lease cleanup, command/exit/XML/count, clock/transport scenario별 결과, source/built/running/first2turns/NOT_RUN을 나눠 보고한다. 이미 충족된 계약이면 NO_CHANGE로 닫는다. offline PASS를 production 부하 proof로 승격하지 않는다.

P2 UI(이번 P0 필수 아님): Gemini `enabled/requested/attempted/succeeded/empty/timeout/skipped`와 skip reason, queueWaitMs/rateWaitMs/wireMs/retryWaitMs만 관측값으로 표시한다. 설정 ON은 실제 attempt가 아니다. 기본 화면에는 짧은 대기/부분 검색 안내, 상세 진단에만 provider·reason·phase breakdown. raw query/body/history/secret은 표시하지 않는다.

strict/OAuth 보호 근거: ChatWorkflow `:1406-1423,7331-7334`의 owner별 catalog/strict·단일시도와 `main/java/com/example/lms/llm/ChatGptOAuthRegistration.java:308-320`의 cancelled/deadline/usage-block 분류를 유지한다. OAuth 선택은 provider 무제한 보장이 아니다.

## Acceptance — 기존 mock/Clock/MockWebServer 도구 우선

| 사례 | 필요한 assertion |
|---|---|
| 2/3/5 user 작은 burst | owner/global admission, provider active/queue bounds, 실제 arrival pacing, logical count≠wire count. B가 A flood에 기아되지 않음. 무제한/실RPS 성능 주장은 금지. |
| provider429 | header 있는/없는/malformed Retry-After, rate vs daily quota. deadline/attempt/shared quota 상한, retry layer1, herd0. jitter/clock deterministic. |
| one down / both down | 건강 provider 진행, 기존 fallback 순서, no fake results, executed_empty vs timeout/disabled/filter-starvation 분리. generation reserve 보존. |
| queued cancel / running cancel | queued outbound0; A cancel 후 B 독립 진행. exactly-once 반환, negative/overflow permit0; running 실제 I/O 종료까지 active 유지, late body/trace/memory/replay overwrite0. |
| user isolation / singleflight | 동일 session/key owner namespace 보존, private query/history/tenant 혼입0. 공용 동일 key만 wire1, A waiter cancel이 B waiter/result를 취소하지 않음. |
| queue full / fallback deadline | 빠른 local reason, 남은 budget 안 기존 fallback 또는 정직한 종료. enqueue가 HTTP attempt 소비 아님. deadline 뒤 wire0, fallback의 shared budget 누락0. |
| recover half-open | fake Clock advance 전 wire0, advance 후 bounded probe, 성공 시 회복, 실패 시 기존 open; 전 사용자 동시 probe 폭주0. |
| 첫 2턴 A→B 품질 | 동일 owner/session의 첫 설명 질문 A와 즉시 구체 후속 B를 preserved fixture로 실행. A 유용한 답변과 B의 질문별 body span→filter→packing→실제 recording-model dispatch→citation→stored/reload를 함께 검사. count/UI URL만 PASS 금지. 기존 repaired relation/stop→R2 fixture를 보존. 다른 owner C와 섞이지 않음. |

아래는 **후속 실행 명령 후보, 이번 NOT_RUN**. 각 test fixture가 loopback fake transport/recording model을 사용하고 외부 credential을 로드하지 않는지 먼저 읽는다. 현재 suite를 확장하며 새 검증 runner/framework를 만들지 않는다. mock가 외부 요청으로 빠지는 경우 그 테스트만 HOLD. 실제 모델 호출 없이 quality의 의미/dispatch 계약을 확인한다.

우선 재사용할 fixture 근거: `src/test/java/com/example/lms/service/web/SearchProviderHttpContractTest.java:57-103,304-343,461-608,713-737`의 loopback HttpServer는 logical/wire 상한·429·Retry-After·socket timeout·Naver delayed subscribe·공유 waiter를 검증한다. `src/test/java/com/example/lms/api/ChatApiControllerSyncLifecycleTest.java:34-123,158-197`는 A의 delegate가 남아 있는 동안 B 성공/permit 점유를 검사한다. `src/test/java/com/example/lms/service/ChatWorkflowPromptMessageRoleTest.java:168-250`는 같은 owner A→B의 실제 model-input relation/qualifier/source/history 경계를 검사한다. 이름이 존재한다는 사실만으로 위의 새 burst/queue 계약을 이미 통과했다고 쓰지 않는다.

```powershell
# ProjectRoot에서, 협동 verifier/host-local build output 정책을 먼저 확인
.\gradlew.bat --offline :test --tests 'ai.abandonware.nova.orch.aop.ProviderRateLimitBackoffAspectTest' --tests 'com.example.lms.service.web.NightmareBreakerProviderPermitBoundaryTest' --tests 'com.example.lms.infra.resilience.NightmareBreakerCallPermitTest'
.\gradlew.bat --offline :test --tests 'com.example.lms.search.provider.HybridWebSearchAdmissionContractTest' --tests 'com.example.lms.search.provider.HybridWebSearchDeadlineBudgetTest' --tests 'com.example.lms.search.provider.HybridWebSearchRequestBudgetTest' --tests 'com.example.lms.search.provider.HybridWebSearchAttemptOwnershipTest' --tests 'com.example.lms.search.provider.HybridSearchExecutionTest' --tests 'com.example.lms.service.NaverSearchSyncBudgetTest' --tests 'com.example.lms.service.NaverSearchServiceInterruptContractTest' --tests 'ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetrieverTimeoutTraceTest'
.\gradlew.bat --offline :test --tests 'com.example.lms.api.PublicChatAdmissionGuardTest' --tests 'com.example.lms.api.ChatGenerationAdmissionFilterTest' --tests 'com.example.lms.api.PublicRequestBudgetProjectionFocusedTest' --tests 'com.example.lms.service.rag.SelfAskSearchBudgetTest' --tests 'com.example.lms.search.provider.HybridWebSearchProviderBoundedFallbackTest' --tests 'com.example.lms.learning.gemini.GeminiGatewayContractTest'
.\gradlew.bat --offline :test --tests 'com.example.lms.service.web.SearchProviderHttpContractTest' --tests 'com.example.lms.api.ChatApiControllerSyncLifecycleTest' --tests 'com.example.lms.service.chat.ChatRunAdmissionConcurrencyTest' --tests 'com.example.lms.service.chat.ChatRunRegistryTerminalIsolationTest' --tests 'com.example.lms.service.rag.WebSearchRetrieverDeadlineTest'
.\gradlew.bat --offline :test --tests 'com.example.lms.service.rag.extract.PageContentScraperTest' --tests 'com.example.lms.service.rag.WebSearchRetrieverRelationEvidenceTest' --tests 'com.example.lms.service.ChatWorkflowPromptMessageRoleTest' --tests 'com.example.lms.prompt.StandardPromptBuilderConversationHistoryTest' --tests 'com.example.lms.prompt.StandardPromptBuilderEvidenceMetadataTest' --tests 'com.example.lms.search.provider.HybridWebSearchQueryBehaviorTest'
.\gradlew.bat --offline :test --tests 'com.example.lms.service.ChatWorkflowStrictSingleAttemptHttpIntegrationTest' --tests 'ai.abandonware.nova.orch.llm.ChatGptOAuthRedTeamContractTest'
```

각 WP는 필요한 가장 작은 suite부터. count0/skipped/UP-TO-DATE만이면 acceptance 실행 증거가 아니다. source hash/실행 receipt/XML를 묶고 multiuser scenario가 실제 assertions에 연결됐는지 보고한다. 첫2턴·controller/run/replay 및 latest search-body preservation suite도 WP0에서 최신 FQCN/fixture를 재확인한다. disconnect와 explicit Stop을 혼동하지 않는다: registry `main/java/com/example/lms/service/chat/ChatRunRegistry.java:567-570`의 explicit Stop 정책과 상충하는 오래된 detach-cancel 기대는 baseline에 몰래 섞지 않는다. 실제 UI/운영 burst·paid live 검증은 이번 범위 밖이며 별도 승인·총 호출/비용 budget/중단 기준이 필요하다. 오프라인 통과 뒤 Java live 반영은 기존 DevWatch/ForceRestart/Verify-RAG 계약에 따른 별도 runtime 단계로 남긴다.

## HOLD / 금지

- foreign live lease/파일 drift: 겹친 파일만 HOLD. 전역 작업중단·강제 해제·옛 patch 재적용 금지. 단일 source writer/target-scoped preimage/hash 필수.
- multi-replica가 확인되면 distributed credential limiter 필요성만 HOLD; Redis/DB 전면 변경·새 daemon·새 orchestration을 자동 도입하지 않는다.
- actual Brave plan/key scope·새 유료 congestion route·cost ceiling 미확인: 그 옵션만 HOLD. 가입/결제/플랜 전환/키추가/paid live calls0. 무료 quota 우회 계정 회전 금지.
- Gemini Google AI Studio 06:48 UTC 알림은 프로젝트 미상 **compatibility risk HOLD/후속**. 우리 demo 장애 원인이라고 쓰지 않는다. `GeminiGateway.java:257-264` 검색 rewrite body에는 contents/선택 tools만, `:449-450` expansion1회; 현재 검색 path에서 알림의 deprecated 필드를 관찰하지 않았다. nativeChatBody `:1183-1186`의 temperature/topP, 다른 purpose의 generationConfig는 별도다. [현재 모델 가이드](https://ai.google.dev/gemini-api/docs/whats-new-gemini-3.5)는 모델별 sampling 제거/thinkingLevel 사용과 compatibility 예외를 설명한다. 알림의 project/model/API surface/serialized field names만 redacted하게 확인하며 전 모델 일괄 삭제·전면 API 전환은 하지 않는다.
- timeout 무조건 증대·전체 사용자 globalserial·strict 우회/모델 강제 전환·auth gate 추가·admin-login-block 완료조건·PromptBuilder 우회·근거/상태 위조·secret reads/노출·전체 test suite·제품 patch/restart/commit/push는 이번 문서 작성 범위에서 금지.

## 조사 시점 pin / lease

2026-10-07 07:16 UTC 읽기 snapshot SHA12(적용 시 재확인): PublicChatAdmissionGuard=619275dfcb2c; ChatGenerationAdmissionFilter=dc5b5b060a9c; PublicRequestBudgetGuard=c6138baa7dad; ChatApiController=1295fcfe300c; ChatRunExecutionContext=2981988f5242; ChatWorkflow=fd8fd72e064b; SearchExecutorConfig=4ba42ea6d77b; NovaAnalyzeWebSearchRetriever=8939dc55be0e; HybridWebSearchProvider=e57837f41980; HybridSearchExecution=e032a9c9f4f4; NaverSearchService=c73425fc2a53; BraveSearchService=6bd645e9804e; SelfAskSearchBudget=98ed3a487256; ProviderRateLimitBackoffAspect=1142247838dd; RateLimitBackoffCoordinator=e0efc6c975d7; GeminiGateway=82de3e133b82; StandardPromptBuilder=a21adb933458.

읽기 시 live source lease: `codex-p0-packing-c9e27e88`는 StandardPromptBuilder/관련 tests, `session469-evidence-reload-green`은 ChatApiController/trace·session detail/UI 관련 targets. owner/topic을 유지하고 적용 시 target manifest로 다시 판단한다. 이번 작성자의 제품 lease0. shared device-bus start는 resource-operation-rejected로 evidence_needed; 문서 조사/작성에는 dependency가 아니며 우회/서비스 시작은 하지 않았다.

완료 조건: 지시서 UTF-8 readback/크기/SHA12/문서 lint 확인과 요청된 Downloads 전달. 구현·운영 acceptance는 별도 NOT_RUN으로 남긴다.

문서 검사 한계: 원본 dot_brief_check는 공식 외부 URL 안의 docs 경로를 로컬 파일로 오인하는 FAIL을 낸다. URL 출처와 실제 로컬 대상을 별도로 대조하며 이 FAIL 원본은 유지한다. 외부 링크를 고치거나 가짜 파일을 만들어 통과시키지 않는다. live lease/readability 경고는 후속 적용 참고이며 문서 저장을 막지 않는다. staged method와 plugin usage 검사는 별도로 기록한다.
