# /chat 스트림 timeout 사슬 지도 (DV2)

작성: devin-chat-timeout-assist-44f7a8c5, 2026-10-02 ~12:2x KST. 읽기 전용 관찰 — 제품 소스 미변경.
대상 경로: 브라우저 `chat.js` → `POST /api/chat/stream` (Spring MVC, `Flux<ServerSentEvent>`) →
`PublicRequestBudgetGuard` → `ChatApiController.chatStream` → `ChatWorkflow.callWithRetryReportingSuccess*`
→ `DynamicChatModelFactory.lcWithTimeout` → (`LlmRouterAspect.routeLocalInference` sharedLocalFailover 시에만)
→ `OllamaNativeChatModel` → Ollama.

## timeout 표

| 계층 | file:line | property 키 | 실제 값 | 초과 시 결과 | 누가 먼저 끊나 |
|---|---|---|---|---|---|
| 프런트 SSE-시작 전 fail-fast | `main/resources/static/js/chat.js:6896-6899, 6943-6946` | — (파생) | `min(5000, clientDeadlineMs)` = **5s** | `stream_client_deadline` 에러 → abort → `recoverExactRunAfterTransportLoss`(chat.js:1774) | SSE 미개시 시 5s — **최단** |
| 프런트 총 client deadline | `chat.js:6789-6799, 6856-6901` | meta `chat-request-budget-ms` (`templates/chat-ui.html:6` ← `PageController.java:50` 기본 300000 = `public.request-budget.max-time-budget-ms`) | `min(300000, 30000)` = **30s** | `stream_client_deadline` → cancel + abort | 30s (SSE 개시 후에도 동일) |
| 프런트 stale 표시 | `chat.js:277, 6884` | `STREAM_STALE_WAIT_MS` | **60s** | UI `model_wait` 표시만(차단 아님) | 차단 아님 |
| BFF 프록시 | `frontend/src/lib/bff.js:2, 75-115, 188-199` | `RAG_BACKEND_TIMEOUT_MS` | **없음 확인**: 로컬 `:18180`은 Spring이 `static/js/chat.js`를 직접 서빙 — Next BFF 미경유. 경유한다면 stream=timeout 0(무제한), non-stream=env 또는 15000 | abort → `backend_timeout` JSON/SSE 본문(bff.js:190) | 비경유 |
| Spring async (서블릿) | `main/resources/application.properties:337-338` | `spring.mvc.async.request-timeout` | **180000ms** | async request timeout | 180s — 사실상 외곽 |
| 서버 요청 예산 (필터) | `main/java/com/example/lms/api/PublicRequestBudgetGuard.java:69-71, 215-219, 352-384` | 헤더 `X-Budget-Ms`; cap `public.request-budget.max-time-budget-ms`=300000 (`application-llm.yaml:848`); `/api/chat/stream` endpointLimit=`min(300000,30000)` | 클라이언트가 항상 `X-Budget-Ms:30000` 전송(chat.js:6801-6804) → **TimeBudget 30s** | body-read/입구 `public_request_deadline_exhausted`(guard:1453); 하위는 `capRequestWait` TIMEOUT_SOFT | 30s — 요청 시작 기준 하드 상한 |
| 모델 호출 타임아웃 | `main/java/com/example/lms/service/ChatWorkflow.java:343-346, 7023-7034` + `main/java/com/example/lms/llm/RequestedModelTimeoutPolicy.java:9-29` | `llm.timeout-seconds`=12 기본(yml 무설정; `application-ultra.properties:324`=18은 local 프로파일 비적용), `llm.requested-model.timeout-seconds`=30 | strict qwen3.5:9b: `min(30, min(12,30))` = **12s**, 이후 `min(예산 잔여)` | 재시도 루프 안에서 실패 누적 | **~12s — 사실상 최단 서버측** |
| TimedChatModelCaller wait | `main/java/com/example/lms/llm/TimedChatModelCaller.java:77-88, 109, 135-164` (호출처 ChatWorkflow:7218,7367,7448) | — | `min(callTimeoutMs, budget.remainingMillis())` | `TimeoutException` → terminalReason `request_deadline_exhausted`/`provider_timeout` → hardTimeout | ~12s |
| Ollama transport (WebClient) | `main/java/com/example/lms/llm/OllamaNativeChatModel.java:555-574` | — (코드) `waitMs = capRequestWait(timeout.toMillis())` | `min(12s, 예산 잔여)`; `beforeCommit` 예산 만료 체크(562-564) | `TimeoutException`/TIMEOUT_SOFT → `timeout_before_first_token`(978) | ~12s |
| Reactor Netty envelope | `application.properties:339-340` | `spring.reactor.netty.http.client.connect-timeout`=60000, `response-timeout`=180s | 60s / 180s | connect/response timeout | `.timeout(waitMs)`가 항상 먼저 |
| LocalModelAdmission | `LlmRouterAspect.java:617-622, 730-739` | `llm.local-admission.enabled`=true, `permits-per-model` | **대기 없음** — `tryAcquire` 실패 시 즉시 `LlmGatewayException("Local model admission slot contended")` | 즉시 거절 | 즉시(경합 시) |
| FallbackAware 예산 가드 | `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java:146-151, 208-216, 240-247` | `TimeBudgetContext` | 잔여≤0 → terminal `request_deadline_exhausted`; fallback 루프 시도 ≤4(203) | fallback 중단 + terminal | 30s 예산 종속 |
| LlmRouterAspect route timeout | `LlmRouterAspect.java:1266, 1563-1576` | `ca.timeoutMs`/`cueTimeoutMs`(호출 인자) | primary=설정값 그대로; fallback role만 `min(설정, 예산 잔여)` | route 실패 → 후속 route/fallback | 예산 종속 |
| run ack | `ChatApiController.java:107, 2096` | `CHAT_RUN_CLIENT_ACK_TIMEOUT_MILLIS` | **3s** | ack 미수신 처리 | 사용자 지연 무관 |

## `backend_timeout` 문자열 생성 위치 (전부)

| 위치 | 경로 |
|---|---|
| `main/java/com/example/lms/llm/ModelSelectionException.java:45` | `failure()`: `LlmFailureClass.TIMEOUT_SOFT → "backend_timeout"` — 호출처 `LlmRouterAspect.java:196`, `ChatWorkflow.java:3753`, `PolicyBasedModelRouter.java:566` |
| `ModelSelectionException.java:21-30` (`streamFailureCode`) | 기존 `ModelSelectionException.code()` 추출 — SSE `error` 이벤트 발송 `ChatApiController.java:2873, 2967` |
| `main/java/com/example/lms/assist/JevEvaluationRuntime.java:354` | Jev 평가 경로 직접 throw — 메인 /chat 경로 아님 |
| `main/java/com/example/lms/service/rag/handler/JevRetrievalGateHandler.java:106` | Jev retrieval gate — 메인 /chat 경로 아님 |
| `frontend/src/lib/bff.js:190` | BFF abort → body `error:"backend_timeout"` — 로컬 :18180 경로는 BFF 미경유 |
| `chat.js:476-477` | 수신된 streamCode `backend_timeout` → failureKind `timeout` 렌더링(생성 아님) |

참고: 서버측 세부 reasonCode(`request_budget_exhausted`, `request_deadline_exhausted`, `provider_timeout`, `timeout_before_first_token`, `provider_execution_uncertain`)는 모두 `TIMEOUT_SOFT`로 묶여 SSE 코드 `backend_timeout` 하나로 나간다 — **UI 코드만으로 어느 층이 끊었는지 구분 불가**.

## 결론

**가장 먼저 끊는 쪽(같은-요청 로그로 확인):** `OllamaNativeChatModel`의 **~12s 모델 호출 타임아웃**(`TimedChatModelCaller.chat` → `.timeout(waitMs)`, `llm.timeout-seconds`=12). 실증: 2026-10-02 11:28 스모크 요청 `hash:8d4bc7e96cd9`는 `http_client_started`(11:28:54.212) → `http_client_failed`(11:29:05.918) = **11.706s**, `failureClass=timeout_soft`, `providerReceiptObserved=false` 전 구간; Ollama는 모델 로드 중(11:28:56.6 시작) 클라이언트 단절로 `POST /api/chat 499 12.132s`를 기록했다(→ `smoke1-replay.json`).

**보강 사실:** qwen3.5:9b 콜드 로드 중앙값 28.85s/최대 31.16s(n=14, abort 33회 별도, `ollama-load-stats.json`) — 12s 모델 타임아웃보다 크므로 **콜드 상태 /chat 요청은 로드가 끝나기 전에 항상 TIMEOUT_SOFT로 잘린다**. 30s 요청 예산·180s async·3s ack·무제한 BFF는 전부 더 바깥 상한이라 먼저 끊지 않는다.

**68.7초 관측치 해석:** 서버는 요청 진입 ~12.4s 후(11:28:53.5 HTTP-IN → 11:29:05.9 stream-failed)에 이미 `ModelSelectionException`으로 끊었다. 68.7초는 차단 시각이 아니라 **스크린샷/관측 시점**(실패 UI가 떠 있는 화면의 캡처 타이밍)일 가능성이 높다 — 단일 한도와 일치하지 않으므로.

**반례 하나:** 이번 증거는 strict(qwen3.5:9b)·콜드·로컬 단일 케이스다. 서버 예산 30s 만료(`request_deadline_exhausted`)도 같은 `backend_timeout`으로 표면화되므로, 예산이 먼저 끊는 시나리오(로그상 `providerReceiptObserved=true`인 요청)에서는 먼저 끊는 쪽이 달라진다 — 확정은 요청별 `smoke1-replay` 재실행으로만.

## 후보(문서 기록만, 변경 금지)

- `llm.timeout-seconds`(12s)가 qwen3.5:9b 콜드 로드(중앙 28.85s)보다 작은 것이 1차 구조적 원인 후보. 대안 후보: warm-up/keep-alive 선로드, `providerReceiptObserved=false` 구간의 로드-진행 취급. 값 변경은 코덱스 WP3 정책 몫.


## 후보(문서 기록만, 변경 금지)

- `llm.timeout-seconds`(12s) 또는 `public.request-budget` 30s 상한은 **후보** — 코덱스 WP3 정책(값 증가 금지, warm-up/heartbeat 재사용 우선)에 따라 실제 수정 여부는 코덱스 결정.
