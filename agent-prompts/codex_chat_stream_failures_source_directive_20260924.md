# Codex 소스수정 지시서 — /chat 스트림 2종 실패 (2026-09-24)

- 작성: Devin (영상 2건 + 라이브 트레이스 + dev-reload 로그 + 소스 대조 완료)
- 대상 체크아웃: `C:\AbandonWare\demo-1\demo-1\src` (Project Root)
- 모드: DRAFT — 기존 소스를 읽고 **최소 패치**로 수정할 것. 새 오케스트레이터/스택 신설 금지.
- 선행 규칙: `AGENTS.md` 전체 적용 (work-ledger, conditional-local-git, 모델 락, spend guard).

---

## 0. 증상 요약 (라이브 증거)

| 시간(KST) | 입력 | 모델 | 결과 | 화면 메시지 |
|---|---|---|---|---|
| 11:23:1x → 11:25:10 | "안녕? 나는 이준우라고해" (RAG ON) | `smtek/Qwen3.8-27B:Q3_K_XL` (요청) | `Stream timeout backend_timeout` | "응답 대기 시간이 초과되었습니다…" |
| 11:30:32, 11:30:45 | 동일 질문 (RAG ON) | `gemma4:26b` | `stream_failed` ×2 | "응답이 완료되지 않았습니다…" |
| 11:25:38~11:26:00 | `GET /api/chat/sessions/{id}` 폴링 | — | HTTP 500 ×4 | — |

근거 파일:
- 세션 트레이스: `var/debug/chat-session-traces/20260924/s-c2356069e9d1.json` (backend_timeout 건),
  `s-b7a56873cd77.json`, `s-5f9c4ab08cac.json` (stream_failed 2건)
- 앱 로그: `var/rag-launcher/20260924-112003-c0bcdb50/chat-ui-vibe-listener-18180.out.log`
  - L10732 `11:25:10.689 stream-failed type=ModelSelectionException`
  - L10816 `11:25:38.916 NoClassDefFoundError: com/example/lms/api/ChatSessionDetailResponseBuilder` (ClassNotFoundException 스택, `ChatApiController.getSessionResponse:4973`)
  - L12289/L12332 `11:30:32/45 stream-lifecycle-failed type=NoClassDefFoundError`
- DevWatch 로그: `var/dev-reload/dev-reload.log` — 11:25:10/11:26:16/11:27:06 `:compileJava :processResources` **3연속 compile-failed exit=1** → failStreak=3 → 5분 pause → 11:32:56 rebuild 성공 → 11:34:01 재시작
- `ollama ls` 확인된 설치: `smtek/Qwen3.8-27B:Q3_K_XL`(14GB), `gemma4:26b`(17GB) 등. Ollama 11434 + 11435 양쪽 구동 중.

---

## 1. 확정 원인 (사실/추정 구분)

### Defect A — `backend_timeout` (영상1) — **사실**

요청 전체 예산이 LLM 콜드 로딩을 수용하지 못함:

1. 프론트가 RAG ON 스트림에 `X-Budget-Ms: 120000` 전송 — `main/resources/static/js/chat.js:254-256,6560-6564`
   (`STREAM_SERVER_EVIDENCE_BUDGET_MS=120000`; WEB=30000, MODEL=90000).
2. `PublicRequestBudgetGuard`가 이를 요청 전체 `TimeBudget`으로 설정 — `main/java/com/example/lms/api/PublicRequestBudgetGuard.java:78-79,351-378` (`max-time-budget-ms` 상한 120000).
3. `RequestedModelTimeoutPolicy`는 사용자 지정 로컬 챗 모델에 `max(base,180s)`를 부여하지만 — `main/java/com/example/lms/llm/RequestedModelTimeoutPolicy.java:4,25-27`
   `ChatWorkflow`가 `callTimeoutBudgetMs = min(180s, requestBudget.remainingMillis())`로 재캡 — `main/java/com/example/lms/service/ChatWorkflow.java:6756-6774` (`llm.call.timeout.cappedByRequestBudget` 트레이스 키가 실제 트레이스에 존재).
4. 27B 모델 콜드 로드+생성이 잔여 예산을 초과 → `TimedChatModelCaller` `request_deadline_exhausted` — `main/java/com/example/lms/llm/TimedChatModelCaller.java:77-87,128-157`
   → `LlmGatewayFailureClassifier` TIMEOUT_SOFT → `ModelSelectionException("backend_timeout")` — `main/java/com/example/lms/llm/ModelSelectionException.java:34`
   → `ChatApiController` 2840-2841행 SSE `error("backend_timeout")` → 프론트 timeout 메시지 — `chat.js:440-441,449-451,512`.
5. 전송→실패 약 120초가 `max-time-budget-ms`와 정확히 일치. GPU 3D가 100%→0%로 떨어진 시점과 부합(로딩 중 예산 소진 후 강제 취소).
6. `fallbackCount=0`: 사용자가 모델을 명시 선택(`exactSelection`)하면 `sharedLocalFailover` 조건(`!exactSelection`)에 걸려 **폴백이 아예 구성되지 않음** — `main/java/com/example/lms/llm/DynamicChatModelFactory.java:329-330`.

### Defect B — `stream_failed` + 세션상세 500 (영상2 + 폴링 실패) — **사실(메커니즘) / 추정(삭제 주체)**

실행 중 JVM의 클래스패스가 라이브 증분 컴파일에 의해 파괴됨:

1. 10:56:19 미커밋 대규모 수정이 `ChatApiController.java`에 반영(git diff +800행) — `getSessionResponse`가 `ChatSessionDetailResponseBuilder.build(...)` 호출 — `ChatApiController.java:4973` (호출부), `ChatSessionDetailResponseBuilder.java` (6월 커밋된 정상 소스).
2. 11:20 강제재시작 후 JVM 가동 중, dev-reload가 `main/java` 감시하다가 **다른 에이전트의 라이브 소스 수정**(NamedChatModel/NightmareBreaker/PolicyBasedModelRouter/HybridLlmGatewayProbeService/ModelRuntimeHealthTracker)을 받아 `:compileJava` 3연속 실패.
3. 실행 JVM은 `build/classes/java/main`에서 지연 로딩하는데, 실패한 증분 컴파일이 `ChatSessionDetailResponseBuilder.class` 등을 삭제/미재생성 상태로 남김(추정: Gradle 증분 산출물 정리 후 javac 실패).
   - `.class`가 실제로 생성된 시각 = **11:33:47** (성공 컴파일+재시작 직후). 11:22~11:31 구간엔 부재.
4. 결과: `GET /api/chat/sessions/{id}` → CNFE→NoClassDefFoundError 500 (L10816 스택), `POST /api/chat/stream` → `stream-lifecycle-failed` (L12289/L12332).
   - 스트림 측은 `catch (Exception ex)`(2836행 블록)를 통과한 `Error`가 바깥 `catch (Throwable lifecycleFailure)`(2932행)에 잡혀 `ChatStreamEvent.error("Chat stream failed errorHash=…")` — 코드가 아닌 자유 텍스트라 프론트는 기본값 `stream_failed`로 분류 — `chat.js:487,514`, `ChatApiController.java:2932-2959`.
5. 부가 피해: 실패 안내의 "기존 실행 확인" 버튼(nextAction=check_run)이 `GET /sessions/{id}`를 치는데 같은 누락 클래스로 500 — 복구 경로도 함께 사망.

### Defect C (구조적 리스크, A/B의 상위 원인) — **사실**

- dev-reload는 실행 중 JVM이 읽는 `build/classes/java/main`에 **in-place** 컴파일한다. 컴파일 실패 시 산출물이 부분 삭제된 채 JVM은 계속 서빙 → 지연 로딩되는 모든 참조가 연쇄 CNFE.
- 검증 경로(`scripts/debug_rag_stack.ps1 -Action verify`, freshness=stale-candidate 감지는 이미 존재 — PROJECT_STATUS §3 디버그 행)가 "컴파일 실패로 깨진 라이브 클래스패스"를 하드 실패로 잡지 않음.
- 동시성: 다른 에이전트 세션이 데모 중 `main/java`를 직접 수정 → watch가 자동 리빌드 → 라이브 붕괴. (이번에는 외부 편집이 원인이지만 메커니즘은 동일.)

---

## 2. 수정 지시 (우선순위)

| # | 상태 | 항목 | 목표 |
|---|---|---|---|
| P0 | 확정 | Defect B — 라이브 클래스패스 보호 | 컴파일 실패가 실행 중 서비스를 깨지 못하게 + 실패 시 즉시 탐지 |
| P1 | 확정 | Defect A — 요청 예산 vs 모델 타임아웃 모순 | 사용자 지정 대형 로컬 모델이 최소한의 완료 기회를 갖게 |
| P2 | 확정 | 에러 분류 정확도 | `NoClassDefFoundError`/`LinkageError`가 `stream_failed`로 뭉개지지 않게 |
| P3 | 조건부 | 폴백 정책 | 정책 판단 필요 — 아래 §3.4 트리거 조건 참조 |

### P0-A. 컴파일 실패 시 라이브 클래스패스 비파괴 (최소 방어)

- 현재 심: dev-reload → `gradle :compileJava :processResources`가 `build/classes/java/main`을 in-place 갱신.
- 요구 수정(택 1, 기존 심 우선):
  1. dev-reload 빌드 스크립트가 컴파일 실패를 감지하면, 다음 성공 컴파일까지 "클래스패스 손상 가능" 상태를 기록하고 `verify`가 그것을 하드 실패로 보고하게 할 것. 최소 구현: 실패 시 `var/dev-reload/last-compile-failed.json`(시각+exit+대상 소스) 기록, 성공 시 제거; `debug_rag_stack.ps1 -Action verify`에 체크 추가.
  2. 또는 컴파일 산출물을 스테이징 디렉터리에 만들고 성공 시에만 동기화(robocopy/rsync 미러) — 단, 실행 JVM이 동일 경로를 읽는 구조라 완전한 원자성은 아님. 선택 시 1번과 병행.
- **금지**: JVM 핫스왑 에이전트 도입, devtools 추가, 새 빌드 시스템.

### P0-B. `ChatSessionDetailResponseBuilder` 호출 경로 정상화 확인

- 원인은 런타임 클래스패스이지 소스 결함이 아님. 단, **현재 트리에서 `getSessionResponse`→`ChatSessionDetailResponseBuilder.build(...)` 가 정상 컴파일·로드되는지**를 확인하고, 미커밋 ChatApiController 변경분(+800행)이 다른 신규 참조를 추가했다면 동일 방식으로 빠진 클래스가 없는지 점검할 것.
- 확인 방법(컴파일 후): `GET /api/chat/sessions/{id}`가 500이 아닌 정상 응답/404를 반환.

### P1. 요청 예산 ↔ 모델 타임아웃 정합성

- 대상 심: `ChatWorkflow.java:6756-6774`(callTimeoutBudget 캡), `TimedChatModelCaller.java:77-87`(잔여예산 캡), `chat.js:254-256,6560-6564`(X-Budget-Ms 선택), `PublicRequestBudgetGuard.java:78-81`(서버 상한).
- 요구 수정(아래 중 합의된 최소안 — 구현 전 `docs/API_ROUTING_SPEC.md`/`configs/api-routing.yaml`에서 최신 값 재확인, mutable-spec 정책 적용):
  1. 사용자 **명시 선택** 로컬 모델이면서 `RequestedModelTimeoutPolicy`가 180s급을 부여하는 경우, 요청 예산이 그것보다 작으면 **스팀 시작 시점에** 예산 부족을 조기 실패(`chat_model_budget_exceeded` 계열의 기존 코드 재사용)로 알리거나,
  2. EVIDENCE 예산(120s)에서 검색 단계가 먹는 시간을 차감한 잔여가 모델 최소 필요 시간 미달이면 동일하게 조기 실패 + 프론트가 "대형 모델 로딩 중" 진행 신호를 표시할 수 있게 할 것,
  3. 또는 `public.request-budget.max-time-budget-ms`/`STREAM_SERVER_*_BUDGET_MS` 상향 — **스펙 값이므로 YAML/스펙 문서와 함께 갱신하고 상한 검증(`requireConfigured` ≤3,600,000) 범위 유지**.
- 무엇을 택하든: 타임아웃 발생 시 `llm.call.timeout.*` 트레이스는 유지(회귀 금지), `terminalReason`의 `request_deadline_exhausted`/`provider_timeout` 구분 유지.

### P2. LinkageError 분류

- `ChatApiController.java:2932-2959` 생명주기 캐치에서 `NoClassDefFoundError`/`ExceptionInInitializerError`/`UnsupportedClassVersionError` 등 `LinkageError` 계열을 구별해 SSE 에러 코드로보낼 것 — 예: 기존 `backend_unavailable` 코드 재사용(프론트 `chat.js:435-438,497`이 이미 전용 메시지 보유) 또는 신규 코드 `server_runtime_stale` 추가 시 `chat.js` `classify`/`chatFailureMessage`/`ModelSelectionException.CODES`에 함께 등록.
- REST 측(`getSession` 등 dispatcher 경로)은 그대로 500 두되, 에러 로그에 클래스명 해시가 아니라 **클래스명 자체**는 남겨도 되는지 SafeRedactor 정책 확인(클래스명은 비밀 아님 — 현재 dispatcher 로그가 이미 평문 출력 중).

### P3. (조건부 — 판단 필요, 자동 진행 금지)

- `exactSelection`(사용자 지정) 경로는 `sharedLocalFailover`에서 제외됨(`DynamicChatModelFactory.java:329`). 사용자 고정 선택에 대한 예산소진/소프트 타임아웃 시 **1회 한정** 기본 로컬 모델(`llm.chat-model` 기본값) 폴백 허용 여부는 정책 결정 사항 — `$demo1-gpu-power-fallback`의 로컬 재시도 ≤1 규칙과 `api-routing.yaml` 순서를 따를 것. 사용자 명시 선택을 묵인 변경하는 폴백은 "선택한 모델" 계약을 깨므로, 프론트에 폴백 발생을 표시하는 기존 breadcrumb(`llm.gateway.selectedRoute`, `maskedFallbackReporter`) 재사용 필수.

---

## 3. 금지 경계 (no-change boundary)

- Git 명령 전면 금지(읽기 포함). 이번 지시서 범위에서 `git` 호출하지 말 것.
- `.secrets/`·자격증명·`X-Owner-Token`·API 키 값 출력/기록 금지. 환경변수 **이름**만.
- 포트(18180/18181/18182/11434/11435)·프로파일(`local,meta-display`)·모델 태그 문자열 하드코딩 금지 — `configs/api-routing.yaml`/`docs/API_ROUTING_SPEC.md`/`ollama ls` 재조회.
- `OllamaNativeChatModel`의 think-false 라우팅, `gemma4:26b` 전용 `reasoningEffort("none")` 분기(`DynamicChatModelFactory.java:386-391`), 임베딩 경로, 렌즈 캡션 계약(`$demo1-meta-display-simple-caption`), Nova Focus `focus` 필드 — **건드리지 말 것**.
- LangChain4j 의존성 버전 변경 금지(1.0.1 고정), `dev.langchain4j` 베타/0.x 추가 금지.
- `FallbackAwareChatModel`의 최대 폴백 횟수(1~3 클램프) 및 `failover_exhausted` 종결 의미 변경 금지.
- 이미 검증된 심(선택엔트로피, 예산 가드 카운트, 트레이스 키 스키마 `awx.chat-session-trace.v1`) 재작업 금지.

## 4. 검증 계획 (필수)

1. **컴파일**: `.\gradlew.bat :compileJava :processResources -x test` (프로젝트 루트 `src`에서).
2. **단위/계약 테스트**: 기존 `src/test/js/chat-failure-classification.test.cjs`, `src/chatUiTest/.../ChatFailureClassificationFocusedTest.java`, `scripts/chat_ui_stream_contract_tests.js` 중 해당 분류 관련 항목 실행 — LinkageError→신규 코드 매핑 케이스 **1개 이상 추가**.
3. **Defect B 재현 검증(라이브)**: 컴파일 산출물에서 임의 클래스 1개 삭제 → `GET /api/chat/sessions/{id}` 500 재현 → 수정 후(verify 체크) `verify`가 하드 실패로 보고하는지 확인 → 클래스 복원.
4. **Defect A 재현 검증(라이브)**: RAG ON + `smtek/Qwen3.8-27B:Q3_K_XL` 선택으로 동일 질문 전송 → 수정 전: 120s `backend_timeout` 재현 확인 → 수정 후: 조기 실패 코드 또는 연장된 예산으로 **첫 토큰 수신** 확인. 대조군: `qwen3.5:9b`(소형)는 기존처럼 동작.
5. **트레이스 회귀**: 두 경로 모두 `var/debug/chat-session-traces`에 `outcome`/`errorClass`가 새 분류를 반영하는지 `scripts/chat_session_debug_export.py show`로 확인.
6. **실행 반영 증명**: Start-RAG/DevWatch ForceRestart 후 라이브 18180에서 재현 — stale JVM의 200은 증거로 인정하지 않음(AGENTS 규칙).

## 5. 보고 형식

- 변경 파일 목록 + 각 파일 SHA-256(수정 전/후).
- 증거 표를 mock / build / live boot / real API / browser 열로 분리. `not_observed`·`historical` 표기 준수.
- 재현 실패 로그 라인(파일명+라인번호)과 수정 후 대응 로그를 1:1로 대조.
- 미해결 항목은 `not_observed`로 남기고 추측으로 채우지 말 것.

## 6. 참고 (이미 확인된 사실 — 재조사 불필요)

- `X-Budget-Ms` 미전송 시 기본 예산은 `min(addons.budget.default-ms=1500, max=120000)=1500ms` — 의도된 기본값인지 불명(의심 지점, 이번 범위 밖).
- Display 표면(`/api/assist/…`, `X-Budget-Ms: 80000`)의 `gemma4:26b`는 11:11/11:13에 정상 완료(`err=none`) — 모델 자체는 살아있음. chat 표면 실패와 무관.
- `TimedChatModelCaller` 공유 실행자 workers=4/queue=32 — 포화 흔적(`executor_saturated`)은 이번 트레이스에 없음.
