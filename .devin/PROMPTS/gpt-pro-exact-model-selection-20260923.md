# 지시서: `/chat` 선택 모델 고정(exact model selection) + 공개망 검증

대상 에이전트: GPT Pro (소스 수정 담당)
작성 근거: 2026-09-23 실제 소스/리스/핸드오프 실측. 아래 `사실` 항목은 이 체크아웃에서 확인된 것, `추정`은 미확인.

## 범위

- Project Root: `C:\AbandonWare\demo-1\demo-1\src` (다른 상위 폴더를 코드 루트로 잡지 말 것)
- 대상 UI: `https://abandonwareai.kro.kr/chat` (로컬 검증: `http://127.0.0.1:18180/chat`)
- SSOT 핸드오프: `data/agent-handoff/codex-autonomy/chat-repair-dynamic-ui-0868f566/`
  - `pending-exact-model.patch` — 적용 대상 후보 패치
  - `interim-verification.md` — BEFORE 기록지 (AFTER를 여기에 갱신)
  - `phase-e-targets.json` — 대상 파일 SHA-256 핀
  - `journal.json` — hold/blocked 사유 기록

## 이미 끝난 것 (재작업 금지)

- 익명 `/api/chat/**` 접근, 오류 분류, 초안/답변 보존
- 대화 중심 UI(히스토리/모델·검색·RAG/넓은 대화/하단 입력)
- 로컬 Ollama + OpenRouter 공개 카탈로그(미설정 모델은 disabled)
- strict-selection **상류 파이프라인은 이미 소스에 존재**(사실):
  - `main/java/com/example/lms/llm/RequestedModelSelection.java` — `begin(model)`/`matches(model)`, TraceStore 내부키 `chat.internal.exactModelSelection`
  - `main/java/com/example/lms/llm/ModelSelectionException.java` — code 집합: `model_unavailable|provider_not_configured|provider_unauthorized|protocol_unsupported|rate_limited|quota_exceeded`, `failure(Throwable)` 팩토리
  - `main/java/com/example/lms/dto/ChatRequestDto.java:66` — `boolean strictModelSelection` 필드(JSON `strictModelSelection`)
  - `main/java/com/example/lms/service/ChatWorkflow.java:1326-1335` — `RequestedModelSelection.begin(req.isStrictModelSelection() ? req.getModel() : null)` + 카탈로그 `resolve().selectable()` 게이트(미선택가능이면 즉시 `ModelSelectionException`)
  - `main/java/com/example/lms/api/ChatApiController.java:1534` — 동기 경로 `ModelSelectionException` → HTTP 422 `ChatResponseDto(code)`, `:2796` — 스트림 경로 `selection.code()`를 오류 이벤트로
  - `main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java:282,489-508` — 라우터 레벨 exact 경로(`exactRequestedModel`) 이미 구현
  - `src/test/java/com/example/lms/service/routing/ExactRequestedModelTest.java` — 라우터 레벨 테스트 이미 존재
- 집중 테스트: Java 13 + JS 12 PASS(기록됨). 대형 스트림 스위트 전체 그린은 목표 아님.

⇒ **타입(`RequestedModelSelection`, `ModelSelectionException`) 신규 생성 불필요. "없으면 생성" 분기는 해당 없음.**

## 리스/잠금 상태 (2026-09-23 실측, 사실)

`python -B scripts/agent_scope_lease.py who` 결과:

- `madwain-live-verify` 리스는 **expired**(만료 2026-09-22T09:10:33Z)이지만 **여전히 blocking**. `ownerState=unknown`, `ownerProcessId=0`, heartbeat 없음. 만료 리스는 `recover`가 소유자 사망을 증명하기 전까지 대상을 계속 잠금다.
- 잠금 대상 13경로에 두 패치 대상이 포함됨:
  `main/java/com/example/lms/llm/dynamicchatmodelfactory.java`, `main/java/ai/abandonware/nova/orch/aop/llmrouteraspect.java` 외에 `fallbackawarechatmodel.java`, `hybridllmgatewayprobeservice.java`, `modelruntimehealthtracker.java`, `ollamanativechatmodel.java`, `geminigateway.java`, `conversatelocalcardgenerator.java`, `docs/project_status.md`, 테스트 4종.
- `journal.json`에 사용자가 "madwain 종료·예약 해제 예정"이라 확인한 기록이 있으나, 마지막 scoped check는 여전히 `source-target-overlap`를 반환.

### 필수 절차 (임의 해제 금지)

1. `python -B scripts/agent_scope_lease.py who` → `check --path <대상>` 로 현재 상태 재확인.
2. 여전히 blocking이면 `python -B scripts/agent_scope_lease.py recover` — **소유자 사망이 증명될 때만** 회수됨. `recover`가 거부하면(소유자 생존/증명 불가) 작업 중단·보고. `__patch_drop__/source-edit-locks/` 디렉터리 수동 삭제 절대 금지.
3. 해제 확인 후 `phase-e-targets.json`의 sha256과 현재 파일 해시 비교 → 일치 확인 후 `claim --agent gpt-pro --task <newTaskId> --path <편집 대상들>` → `verify` → 편집.
4. 편집 전 `scripts/codex_work_checkpoint.py`로 per-change preimage 보존(work-ledger 규칙), 완료 시 `done`.

참고 해시(2026-09-23 측정, phase-e-targets.json과 일치 확인됨):

| 파일 | SHA-256 |
|---|---|
| `main/java/com/example/lms/llm/DynamicChatModelFactory.java` | `054dcef4ac502ec79a38989f852f471ed3ea040628f28e33be5fce8817ca15db` |
| `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java` | `5cea46215a3aeb72ba58aab3284432b9681150984646b58f75f9226583ee2add` |
| `main/resources/static/js/chat.js` | `217ce8992747fd82f7a0d3ffb7e0198c48d6a5d1ab5b48733c58e3fe53fac7be` |

⇒ 두 Java 대상은 패치 작성 시점과 바이트 동일 — 패치 컨텍스트가 신선함.

## 핵심 작업 (이번만)

후보 패치 적용: `data/agent-handoff/codex-autonomy/chat-repair-dynamic-ui-0868f566/pending-exact-model.patch`

의도: `RequestedModelSelection.matches(...)`가 참일 때 alias 매핑 / `canonicalModelName` 재작성 / `selectLocalBaseUrl` 재배정 / `sharedLocalFailover` / 라우터 자동 대체를 전부 금지. 실패는 fallback이 아니라 `ModelSelectionException`.

### 패치 앵커 (실측 라인, 2026-09-23)

**`main/java/com/example/lms/llm/DynamicChatModelFactory.java`** (`lcWithTimeout` 본문)

- L236 `String effectiveModel = ModelCapabilities.canonicalModelName(rawModel);` 직전에 `boolean exactSelection = RequestedModelSelection.matches(rawModel);` 삽입. exact이면 `llmrouter.*`는 `provider_not_configured`로 throw(라우터 키는 팩토리가 아니라 애스펙트가 소유), 아니면 `effectiveModel = rawModel` 그대로.
  - `canonicalModelName`은 `lc:` 접두어와 ORCH_TAGS(`fallback|evidence|aux|draft|probe|mini|fast|high|low|debug`) 꼬리태그를 벗김(`ModelCapabilities.java:55-78`) — exact 선택에서는 이 재작성이 금지 대상.
- L247 `selectLocalBaseUrl(effectiveModel)` 분기 — exact이면 `localBaseUrl` 고정. `selectLocalBaseUrl`은 모델명 substring으로 fast/high/judge/coder/vision base-url로 재배정(L897-922) — 예: `qwen3.5:9b`→fast, `gemma4:*`→high. 이 재배정이 "선택과 다른 엔드포인트"의 원인.
- L325 `sharedLocalFailover` — `!exactSelection &&` 추가. 켜지면 `localFailoverRouter.routeLocalInference(...)` 래핑 + maxRetries 0 강제가 적용됨.

**`main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`**

- L145 alias 재작성 루프(`resolveAlias`, L1801-1824가 `llmrouter.aliases` 매핑) — exact이면 skip (`&& !RequestedModelSelection.matches(modelId)`).
- L159 `TraceStore.put("llm.gateway.preselectionFallbackCount", 0L)` 직후, "1) Resolve llmrouter.* directly" 이전에 exact 블록 삽입:
  - 비-`llmrouter.` exact → `pjp.proceed()` (팩토리 exact 경로가 처리)
  - `llmrouter.<key>` → `props.getModels().get(key)` 없거나 `!isEnabled()` → `ModelSelectionException("model_unavailable")`, 있으면 `routeWithGateway(new LlmRouterBandit.Selected(key, cfg), ca)`; `RuntimeException` → `ModelSelectionException.failure(e)`
  - 이 블록은 `bandit.pick(...)`(L161)과 `routeWhenAllAutoLocalEndpointsOpen`(L166)을 우회 — 자동 선택 경로 진입 자체를 차단.
- L311 `localLlmProcessManager.isAvailable` 실패 분기 — exact이면 클라우드/디바이스 fallback 시도 전에 `model_unavailable` throw.
- L334 `gatewayProbeService.isEnforce() && !eligible` 분기 — exact이면 **observe 모드(`isEnforce=false`)에서도** `eligibility == null || !eligible` 시 `model_unavailable` throw. (신 테스트 `ineligibleManualRouteCannotChooseHealthyBackupEvenInObserveMode`가 이걸 검증)
- L362 `lazyFallbackPossible` — `!matches(...)` 추가 → `FallbackAwareChatModel` 래핑 자체를 생략(L375-377 조기 return 경로).

**`main/resources/static/js/chat.js`**

- L6291 `sendMessageUnlocked` 페이로드(`model: dom.modelSelect?.value || undefined,`)에 `strictModelSelection: true,` 추가.
- 다른 `model:` 라인들(L1066/2416/5176/5524/5675 등)은 디버그·다른 요청용 — 건드리지 말 것.
- 안전성 확인됨(사실): `#modelSelect` 옵션은 `chat-model-picker.js`가 `option.value = row.id`(서버 `Choice.id`)로 재구성하며 미선택 가능 항목은 disabled; `sendMessage`는 `chatModelCatalogReady` false면 early-return → strict 전송 시 `model`은 항상 구체 ID. `ChatWorkflow:1327` 게이트가 `resolve(null)`→`model_unavailable`을 던지므로 model 없이 strict 켜는 경로가 없는지 한 번 더 확인할 것.

**신규 테스트 `src/test/java/ai/abandonware/nova/orch/aop/ExactModelGatewayTest.java`** (현재 미존재 — 패치가 Add File)

- 3 테스트: observe 모드 ineligible → throw + backup evaluate 0회 / 성공 시 wire model=`exact-synthetic-model`·primary 1회·backup 0회 / upstream 실패 시 primary 1회·backup 0회.
- 생성자 인수 10개는 실제 시그니처(L115-122)와 일치 확인됨.

## 검증 순서

1. `gradlew.bat compileJava` (cwd = 프로젝트 루트)
2. `gradlew.bat test --tests ai.abandonware.nova.orch.aop.ExactModelGatewayTest --tests com.example.lms.service.routing.ExactRequestedModelTest` (+ 영향면의 factory/gateway 테스트)
   - 결과 XML: `build/desktop/test-results/test`(split 출력 사용 시) 또는 `build/test-results/test`. run wrapper의 `totals`가 0으로 나오는 알려진 결함이 있으므로 **XML 파일을 직접** 읽어 PASS 집계.
3. compile·test 통과 후에만 chat.js `strictModelSelection` 활성화(순서 고정 — 서버 no-fallback 없이 켜면 전 요청 422 위험).
4. `Start-RAG.bat` 재기동 (= `scripts/start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -OpenBrowser`; meta-display 프로필, port 18180 / mgmt 18181 / netty 18182). 결과는 `var/rag-launcher/<runId>/result.json`.
5. 로컬 `http://127.0.0.1:18180/chat`에서 1회 생성:
   - UI 선택 ID = 실제 호출 모델/베이스URL. 확인 신호: 응답 헤더 `X-Model-Used`, SSE `modelUsed`, TraceStore `llm.factory.baseUrlHost`/`modelHash`, `llm.gateway.preselectionFallbackCount` = 0, `llm.gateway.fallback.selectedRoute` 부재.
   - upstream 실패 케이스: fallback 호출 0 + 422/`model_unavailable` 계열 코드.
   - 선택 `smtek/Qwen3.8-27B:Q3_K_XL`인데 다른 태그로 나가면 실패.
6. 공개망 `https://abandonwareai.kro.kr/chat`에서 동일 선택 유지 확인(가능하면).

## 공개망 화면 — 관찰·기록만 (이번 패치에 섞지 말 것)

- `Agent DB DISABLED` / `agent_db_context_disabled`: `ChatWorkflow.mirrorAgentDbContextAvailabilityForAgentDebug`(L12155-12166)가 `agentPipelineHealthController` 빈 부재 시 DISABLED 기록. 빈 조건: `agent.db-context.enabled=true` + `AgentDbContextProvider` 존재(`AgentPipelineHealthController.java:51-52`). `application-local.yml`에는 `agent.db-context.enabled: true`가 있음(L229 부근) — 활성 프로필/빈 존재 여부를 조사만 하고 별도 티켓으로. lmsdb 라이브 조회(`/api/internal/db/meta`)와 무관.
- `Chat Harmony warn`: `ChatHarmonyTracePostprocessor`의 `chat.harmony.postprocess.*` → `ChatStreamSignalBuilder` L216-221 → chat.js L4932-4934(`latestDegraded`→WARN). 답변 성공/실패와의 상관만 기록.
- `CFVM Failure queued`: `cfvmFailureRecorder`/`cfvmRawMatrixBuffer` lane(`NovaOrchestrationAutoConfiguration` L221, `ExtremeZBurstAspect`) → chat.js L4986-4991 lane badge. 상관만 기록.

## 금지

- 비밀값 출력/커밋, openssl 키 이름/값/형식/구조 변경
- 남의 lease/lock 디렉터리 강제 삭제, 다른 task journal 기록
- 유료 provider 실호출(명시 전까지 NOT_RUN)
- UI/익명정책 재작업, 대형 스트림 스위트 전체 그린 강요
- `application-local` 인메모리 H2를 Display 영구 DB로 취급
- Git은 읽기 증거로도 이 루프에서 사용하지 않음(commit/push/status/history 전부)

## 완료 기준 / 보고 형식

- pending 패치 적용·`compileJava`·`ExactModelGatewayTest`(+관련) PASS — XML 경로 명시
- 로컬 1회: 선택 모델 = 실제 라우트, fallback 0 — 위 신호값 나열
- 공개망 `/chat` 동일 선택 유지(가능하면; 불가면 사유)
- `interim-verification.md`에 BEFORE/AFTER + NOT_RUN 항목 갱신
- 변경 파일 + SHA-256 표, 증거를 mock/build/live boot/real API/browser로 분리
- 목표가 blocked면 잠금/미검증 이유를 명시하고 completed로 위장 금지
