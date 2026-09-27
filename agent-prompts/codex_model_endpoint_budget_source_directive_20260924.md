# 코덱스 소스수정 지시서 — 모델 선택 endpoint 계약 + 검색 예산 (2026-09-24)

> 작성 근거: GPT Pro 원문이 아니라 **실제 소스를 직접 읽고 라이브 프로브로 대조**한 결과.
> 표기: [사실]=이 체크아웃에서 확인, [추정]=코드상 가능하지만 미재현, [미확정]=RED 테스트로 확정 필요.
> Project Root: `C:\AbandonWare\demo-1\demo-1\src`. Git 제외, 시크릿 출력 금지, 최소 diff.

---

## 0. 대조 결과 요약 (GPT Pro 주장 → 실소스 판정)

| GPT Pro 주장 | 판정 | 실소스 근거 |
| --- | --- | --- |
| 수동 선택 시 `strictModelSelection=true` | [사실] | `static/js/chat.js:6391` — 페이로드에 **무조건** 들어감(AUTO 옵션 없음) |
| `RequestedModelSelection` 존재 | [사실] | `llm/RequestedModelSelection.java` — TraceStore 내부키, `begin`/`matches` (정확 문자열 일치) |
| 카탈로그→라우터→팩토리 경로 | [사실] | `ChatWorkflow:1326` begin → `:1327-1335` `resolve().selectable()` 게이트 → `PolicyBasedModelRouter:282` `exactRequestedModel` → `DynamicChatModelFactory.lcWithTimeout` |
| exact 시 `selectLocalBaseUrl` 건너뜀 | [사실] | `DynamicChatModelFactory:250-251` `exactSelection ? localBaseUrl : selectLocalBaseUrl(effectiveModel)` |
| 이것이 `model_unavailable`의 원인 | [정정] | 현재 배포는 **잠복 결함**일 뿐 즉시 원인 아님 — §2-M3 참고 |
| 모델 이름만으로 selectable 판단 | [사실] | `ChatModelCatalogService:43-105` — `llm.base-url` **단일 엔드포인트만** `/api/tags` 프로브, `endpointId`는 하드코드 라벨 `"local-default"` (:146) |
| 정적 상한 384/4096이 정상 검색 차단 | [사실(구조)] [미확정(실제 429)] | `PublicRequestBudgetGuard:138-141` 기본값, yml 오버라이드 없음. 과대계산식 §3-B1 확정. 실제 429 발생 여부는 RED로 확정 |

---

## 1. 이미 구현된 것 — 재구현·재검증 금지

| 항목 | 위치 | 상태 |
| --- | --- | --- |
| exact 시 alias 재작성 금지 | `LlmRouterAspect:145-153` (`!matches(modelId)` 가드) | 구현됨 |
| exact 시 `canonicalModelName` 생략 | `DynamicChatModelFactory:236-239` | 구현됨 |
| exact `llmrouter.*` → 라우트 cfg 직결 | `LlmRouterAspect:162-170` (route별 base-url 사용 — endpoint 정확) | 구현됨 |
| exact 시 자동 대체 금지(실패=throw) | `PolicyBasedModelRouter:489-509` `exactRequestedModel`; `LlmRouterAspect:329-330,352-354` `model_unavailable` | 구현됨 |
| exact 시 `sharedLocalFailover`/lazy failover 제외 | `DynamicChatModelFactory:329` (`!exactSelection`), `LlmRouterAspect:267-273` (requestedIdentity 유지) | 구현됨 |
| strict+비전 불일치 → `protocol_unsupported` | `ChatWorkflow:1537-1538` | 구현됨(침묵 교체 아닌 명시 실패) |
| `ModelSelectionException` → HTTP 변환 | `ChatApiController:159-166` (@ExceptionHandler→503+reasonCode), `:1548-1551` (sync→422 ChatResponseDto) | 구현됨 |
| 카탈로그 "생성 미검증" honest 표기 | `chat-model-picker.js:123-125` ("설치됨 · 생성 미검증") | UI 표기 이미 존재 |
| 예산 진단 TraceStore | `PublicRequestBudgetGuard:1072-1088` `traceBudget` (retrievalWork/providerWork/topK 기록) | 존재 — **임계값·구성요소는 미기록**(§4-B 개선 대상) |

→ "임의 모델 자동 교체 금지" 계약의 **라우팅 측은 이미 대부분 구현돼 있다.** 남은 구멍은 **엔드포인트 차원**이다.

---

## 2. 확인된 결함 (수정 대상)

### M1. 카탈로그가 `llm.base-url` 단일 엔드포인트만 본다 [사실]
`ChatModelCatalogService` 생성자(:33-40)는 `base`=`llm.base-url`(기본 `http://localhost:11434/v1`→`/v1` 제거) 하나만 받고, `choices()`(:48-79)가 그 호스트의 `/api/tags`+`/api/show`만 프로브한다. `llm.fast/high/judge/coder/vision.base-url`과 `llmrouter.models.*.base-url`은 **어디서도 모델 소스로 프로브되지 않는다**.
- 결과: 기본 엔드포인트에 없고 다른 로컬 노드에만 있는 모델 = 카탈로그에 없음 → UI에 없거나 `selectable=false` → `ChatWorkflow:1330-1333`에서 `model_unavailable`. **"다른 엔드포인트에 설치돼 있으면 찾아 실행" 계약 위반.**
- [라이브 프로브 2026-09-24] `127.0.0.1:11434`와 `:11435`의 `/api/tags`가 현재 **동일 13종**이라 증상이 숨겨져 있다. 노드별 인벤토리가 갈라지는 순간 재현된다.

### M2. `Choice.endpointId`가 실제 엔드포인트가 아니다 [사실]
로컬 행은 전부 `endpointId="local-default"`(:146). `endpoint+modelId` 조합을 표현하는 필드가 없어, 카탈로그가 "이 모델이 어느 엔드포인트에 있는가"를 답할 수 없고 생성 경로와 대조도 불가능하다.

### M3. exact 선택이 `localBaseUrl`에 고정된다 [사실 — 단, 현재 배포에서는 잠복]
`DynamicChatModelFactory:250-251`. AUTO(`selectLocalBaseUrl`, :901-926)는 이름→역할 엔드포인트(coder→coderLocal, `qwen3-vl`→visionLocal, `qwen3.5:9b`/`gemma4:12b`→fastLocal, `gemma4*`→highLocal)로 가는데 exact는 `localBaseUrl`만 쓴다.
- 오늘은 11434≡11435 미러라 실패가 안 나지만, 비기본 노드 전용 모델이 생기면 `model_unavailable`(카탈로그 게이트) 또는 생성 시 404로 드러난다.
- 설정 의존: `llm.fast.base-url`은 env 없으면 `llm.base-url`로 수렴(`application-llm.yaml:51`), `llmrouter.light`는 기본 `11435`(:498), `llmrouter.vision`도 `11435`(:603). **유효값이 환경별로 다르므로 부팅/테스트 시점에 effective config를 출력해 확인할 것.**

### M4. `canServe()`가 로컬 모델의 존재를 검증하지 않는다 [사실]
`DynamicChatModelFactory:183-190` — local 계열이면 무조건 `true`. `PolicyBasedModelRouter:494`의 `provider_not_configured` 사전 게이트는 "미설치 로컬 모델"을 못 잡는다. 설치 여부는 카탈로그 게이트(M1, 단일 엔드포인트)에만 의존한다.

### M5. 응답 model identity 미검증(로컬 경로) [사실]
클라우드 라우트는 `response-model-verification-required`(`application-llm.yaml:516,525,546` 등)가 있지만 로컬 exact 경로에는 응답 `model` 필드 대조가 없다. 요청 모델→선택 엔드포인트→실제 HTTP→**응답 model**까지 일치 추적이 없다(팩토리 breadcrumb는 hash만).

### B1. 예산 과대계산 구조 [사실 — 수치 확정]
`PublicRequestBudgetGuard:527-572` (호출지점: `ChatApiController:1890,4115,4823`):
- `retrievalWork = (ragTopK + webTopK + precisionTopK) × queryMultiplier × modeMultiplier`
- `providerWork = callsPerPhase × hybridMultiplier(한글12/기타6) × webTopK × queryMultiplier × modeMultiplier`
- `queryMultiplier = projectedChatQueryCount` (:633-669): `policy==null→32`, mode별 `maxFinalQueries`(PRECISION 6/BALANCED 10/RECALL 14/DISAMBIGUATE 12, `SearchPolicyEngine:209-262`), extremeZ 최대 +32 → 최대 ~64
- `modeMultiplier`: deep×2, accumulation×2 → 최대 4

구조적 문제(캡만 올리면 안 되는 이유):
1. **`providerWork`에 `webTopK`가 곱해진다** — topK는 "호출당 결과 수"지 호출 수가 아니다. provider 호출량 단위에 topK를 곱하면 단위가 틀어진다.
2. **`callsPerPhase`(:554) 위에 `hybridAttemptMultiplier`(12/6)를 또 곱한다** — callsPerPhase 자체가 초기호출+2×rescue+site-filter 재시도 상한인데, 그 위에 6~12배를 더해 재시도를 이중 계상한다.
3. **`queryMultiplier`가 모든 최종 쿼리가 풀 topK 웹+RAG를 돌린다고 가정** — 실제 phase 호출 상한은 `callsPerPhase`(1~6)인데 10~64를 곱한다.
4. **`policy==null→32`는 "판단 불가"를 최대치로 취급**한다.
5. **`modeMultiplier`가 retrieval/provider 양쪽에 중복 적용**된다.
- [수치] AUTO+한글+RAG ON: `retrievalWork=(8+8)×10=160`(통과), FORCE_DEEP+RECALL(topK≈11): `(11+11)×14×2=616>384` → `chat_retrieval_budget_exceeded`; `providerWork=1×12×11×14×2=3696`, accumulation 추가 시 7392>4096 → `chat_provider_budget_exceeded`. **FORCE_DEEP+RAG+한글은 사실상 항상 차단.**
- 유지할 최종 안전장치(건드리지 말 것): `max-time-budget-ms` 120s + `X-Budget-Ms`, `max-total-token-budget` 65536, body/history/attachment 상한, `callsPerPhase` 자체, 취소 엔드포인트.

---

## 3. 수정 지시 — 기존 컴포넌트 재사용, 새 라우터 금지

### M-FIX-1. 카탈로그를 엔드포인트 인식으로 (ChatModelCatalogService)
- 프로브 대상을 `llm.base-url` 하나에서 **구성된 로컬 엔드포인트 집합**으로 확장: `llm.base-url`, `llm.fast.base-url`, `llm.high.base-url`, `llm.judge.base-url`, `llm.coder.base-url`, `llm.vision.base-url` + `llmrouter.models.*` 중 `provider=local`의 `base-url`(중복 제거, `loopback()` 필터 유지).
- `Choice`에 **실제 엔드포인트 식별자**를 담는다(예: host:port 정규화 문자열 또는 route key). `endpointId="local-default"`는 대체하되 wire 하위호환(필드 추가 또는 의미 유지)은 본인 판단, 기존 consumer(`chat-model-picker.js`, `ChatWorkflow:1328-1333`)와 계약을 명시할 것.
- 프로브 예산: 엔드포인트당 connect/read 타임아웃 유지, 전체 마감(deadline) 보존, 실패한 엔드포인트는 그 엔드포인트 행만 건너뛴다(현행 fail-soft 유지). **새 HTTP 호출로 생성/다운로드를 유발하지 말 것 — `/api/tags`+`/api/show` read-only만.**

### M-FIX-2. exact 생성 경로를 엔드포인트 인식으로 (DynamicChatModelFactory)
- `exactSelection` 분기에서 `localBaseUrl` 고정 대신: **카탈로그가 관측한 (endpoint → model set)에서 해당 모델이 실제 존재하는 엔드포인트**를 선택한다.
  - 모델이 기본 엔드포인트에 있음 → 그 엔드포인트.
  - 다른 구성 엔드포인트에만 있음 → **그 실제 엔드포인트**로.
  - 어느 엔드포인트에도 없음 → `ModelSelectionException("model_unavailable")`.
  - 카탈로그 미주입/미관측이면 `selectLocalBaseUrl(effectiveModel)` 폴백(기존 역할 매핑) → 그래도 안 되면 `localBaseUrl`.
- 재사용: `ChatModelCatalogService`를 `DynamicChatModelFactory`에 주입(선택적 `@Autowired(required=false)` 패턴 준수)하거나, 최소한 동일한 endpoint-set 해석을 **한 군데**(카탈로그 서비스의 공개 메서드, 예: `resolveInstalledEndpoint(modelId) → Optional<endpointId/baseUrl>`)에 둔다. **팩토리·카탈로그·라우터가 각자 다른 모델 존재 판단을 하지 않게 SSOT를 하나로.**
- `routePolicyFailure`(`LocalLlmGatewaySecurity:26-69`)는 enabled 라우트와 (model+endpoint) 매칭을 이미 한다 — exact가 찾은 엔드포인트가 disabled 라우트에 매핑되면 그대로 실패시킨다(변경 불필요, 회귀 테스트로 확인).

### M-FIX-3. `canServe`/selectable 의미 정렬
- `canServe()`에 카탈로그 기반 존재 확인을 붙이거나(로컬), 라우터 `exactRequestedModel`이 카탈로그 resolve 결과를 재사용하게 해 `selectable`과 생성 가능성의 의미를 맞춘다.
- `ModelRuntimeHealthTracker` 게이트(`health_unavailable`)가 일시 실패로 모델을 장기간 비활성화하지 않는지 `isPromotable` 경로를 확인하고, health 차단 시 reason이 UI/진단에 전달되는지 검증할 것.

### M-FIX-4. 응답 identity 추적
- 요청 모델 → 선택된 endpoint(역할/실주소) → 실제 호출 → 응답의 model 필드를 한 요청에서 대조 가능하게 TraceStore에 남긴다(모델명은 hash+length 방식 유지, 엔드포인트는 host/port 라벨·hash, 비밀값 금지). 응답 model 불일치 시 로컬 경로에서도 분류된 실패(reasonCode)로 표면화.

### B-FIX-1. 예산 계산식 우선 수정
- `providerWork`에서 `webTopK` 인자를 제거하거나 결과-아이템 단위와 호출-횟수 단위를 분리한다. "provider 호출 수" 상한이라면 `callsPerPhase × 실제 시도 상한 × queryCount 상한`만으로 계산한다.
- `policy==null→32`를 실제 planner 상한(현실적으로 도달 가능한 쿼리 수 — `ABS_MAX_QUERIES=16`, `tunePlannerMaxQueries` 경로) 또는 보수적 기본값(예: BALANCED의 10)으로 내린다.
- `modeMultiplier`의 이중 적용(retrieval+provider 양쪽)을 한쪽으로 정리하거나, deep/accumulation이 실제로 몇 배의 호출을 만드는지 코드 기준으로 재정의한다.
- `projectedChatQueryCount`의 extremeZ 가산을 중복 가산이 아닌지 확인.
- 상한 조정(계산식 수정 후 필요한 만큼만): 테스트 1인 환경 기준 AUTO / FORCE_LIGHT / FORCE_DEEP / RAG ON 각각에서 **실제 도달 가능한 최대 호출 수 × 여유(2~4배)**로 `max-retrieval-work`, `chat.max-provider-work`를 올린다. yml 키로 노출(현재 @Value 기본값만 존재 — `application*.yml`에 명시 키 추가).
- 진단 강화: `traceBudget`에 임계값(`maxRetrievalWork`/`maxProviderWork`)과 구성요소(queryMultiplier, modeMultiplier, callsPerPhase, hybridMultiplier, webTopK, ragTopK)를 추가해 사후 조정 가능하게 한다.
- **검증 없이 상한만 올리는 패치 금지.** 최종 안전장치(시간 예산, 토큰 총량, body 상한, 취소)는 유지.

---

## 4. 진행 순서 (RED 먼저)

1. `RequestedModelSelection.begin` 이후 선택 modelId와 카탈로그 `resolve()` 결과(endpointId 포함)를 진단에 기록.
2. `DynamicChatModelFactory`가 최종 선택한 baseUrl을 TraceStore에서 대조 — 불일치 케이스를 **RED 테스트**로 작성:
   - (a) 모델이 비기본 엔드포인트에만 존재하는 fixture → exact 선택이 그 엔드포인트를 고르는지(현재 RED → 수정 후 GREEN).
   - (b) 어디에도 없는 로컬 모델 → `model_unavailable` (이미 동작하면 GREEN 유지).
   - (c) exact 선택 후 다른 모델로 조용히 교체되지 않는지(응답 model 대조).
   - 테스트 환경 참고: `application-verification.properties:78-92`는 모든 llm 엔드포인트를 `verification.fixture-port`로 통일 — **엔드포인트별 fixture를 분리해야 RED가 드러난다.**
3. 예산 재현 RED: `PublicRequestBudgetGuardTest`에 AUTO(한글+web+RAG), FORCE_DEEP+RAG, FORCE_LIGHT, Search OFF 각각의 `validateChatProjected` projection 값 단언을 추가 — 현재 차단 여부와 계산 근거를 수치로 고정.
4. M-FIX-1→2→3→4 순으로 최소 수정, 각 단계 후 관련 테스트 실행.
5. B-FIX-1 계산식 수정 → 상한 조정 → 회귀: AUTO / Search OFF / FORCE_LIGHT / FORCE_DEEP / RAG ON 각각 1회 이상 통과.
6. 라이브 검증(가능하면): 선택 모델→엔드포인트→HTTP→응답 model까지 requestId로 추적. `scripts/chat_session_debug_export.py`로 run trace export 첨부.

---

## 5. 회귀 금지 (이전 패치 보존)

- exact 경로의 no-fallback 계약: alias 스킵(`LlmRouterAspect:146`), canonicalize 스킵(`Factory:239`), `exactRequestedModel` throw 경로, `!exactSelection` sharedLocalFailover, `routeLocalInference`의 requestedIdentity(`Aspect:267-273`), 비전 불일치 `protocol_unsupported`(`ChatWorkflow:1537`).
- `LlmRouterAspect`의 `routeWhenAllAutoLocalEndpointsOpen`/`fallback-when-openai-missing` 등 AUTO fail-soft 정책 — 자동 선택일 때만 동작 유지.
- 카탈로그는 생성/다운로드를 유발하지 않는 read-only 특성 유지(`choices()` 주석 :15, :46-47). `discover=true`의 OpenRouter 공개 조회 동작 변경 금지.
- GraphRAG owner/session/channel 격리(`Neo4jKgChunkWriter`/`Neo4jKnowledgeGraphClient` 라인), FocusMemoryScope/MemoryEvidence 보호, exact-run 복구, `/stream` 실패 후 `/sync` 자동 재생성 금지 — 이번 범위 밖, 건드리지 말 것.
- `chat.js:6391`의 `strictModelSelection`은 서버 no-fallback이 완전히 검증되기 전까지 그대로 둔다.
- 예산 최종 안전장치 전체(§2-B1 하단) 유지.

---

## 6. 완료 판정 (증거 요구)

- 클래스 존재·모델 목록 노출·HTTP 200만으로는 완료 아님.
- 수동 선택 각 모델에 대해: **선택 modelId → 카탈로그가 보고한 endpoint → 실제 생성 요청 endpoint → 응답 model identity**가 같은 requestId 안에서 일치하는 증거(로그/trace export)를 제시.
- 미설치 모델 → `model_unavailable`, 미구성 라우트 → `provider_not_configured`, 비전 불일치 → `protocol_unsupported`가 각각 구분된 reasonCode로 표면화되는지 확인.
- 예산: AUTO / OFF / FORCE_LIGHT / FORCE_DEEP / RAG ON 5개 모드의 `validateChatProjected` 계산값과 실제 호출량을 나란히 기록하고, 차단 없이 통과 + 안전장치 잔존을 확인.
- 변경 파일 목록 + sha256, mock/빌드/라이브 부트/실제 API 증거를 분리한 표로 보고.
