# 노트북 소스 수정 지시서 — 미완료 항목 통합 실행본

작성일: 2026-09-08 KST. 실행·최종 검증 소유자: Desktop.

이 문서는 접근 가능한 과거·최근 지시서를 현재 소스와 대조해 남은 일을 다시 정리한 **지시서**다. 이번 작성에서 애플리케이션 소스는 수정하지 않았다. 실제로 재현한 결함, 설계와 구현의 충돌, 아직 없는 구현, 검증만 남은 항목을 구별한다. 아래 순서대로 한 단위씩 실행하며, 이미 있는 기능을 다시 만들지 않는다.

## 1. 현재 계약과 검색 범위

```yaml
contractVersion: demo1.notebook-directive-consolidation.v1
programId: awx-desktop-notebook-remaining-source-20260908
canonicalExecutionRoot: C:\AbandonWare\demo-1\demo-1\src
canonicalDirectivePath: agent-prompts/awx_desktop_notebook_remaining_source_directive_20260908.md
sourceOwner: desktop
activeSourceSets: [main/java, main/resources, app/src/main/java_clean, app/src/main/resources]
activeTestSourceSet: src/test/java
artifactStatus: published
applicationImplementationStatus: remaining_work_explicit
desktopFinalProof: evidence_needed
notebookEvidenceAuthority: supporting_only
originalDeletionAuthorized: false
sourceMutationsThisRun: 0
runtimeStartsThisRun: 0
externalRequestsThisRun: 0
repositoryWideHold: false
nextSingleProof: W01 isolated resolver RED is reproduced; extend the two existing static fixtures with the same local-boot case
```

현재 branch는 `codex/owned-runtime-browser-restart`, HEAD는 `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`이다. Java 17.0.13과 실제 Gradle `projects`, `checkSourceSetHygiene`, `checkLangchain4jVersionPurity`, `compileJava`, 선택 테스트 실행을 확인했다. Spring Boot 3.3.4와 LangChain4j 1.0.1을 유지한다. `:app`의 `java_clean`은 활성 입력이며 통째로 지우는 대상이 아니다.

이번 조사 기준은 다음과 같다.

- `data/agent-handoff/notebook`의 직접 파일, `__patch_drop__/notebook`의 standalone directive, `agent-prompts`의 관련 standalone 문서와 정확히 연결된 보고서·설계 문서: **27개**. 날짜 제한 없이 찾았다.
- canary, 재사용 prompt, PatchDrop sidecar, 디렉터리 등 **56개 항목**을 실행·삭제 후보에서 제외했다. 디렉터리 하나를 개별 파일 수로 세지 않는다.
- 8월 통합본의 원본 계보 **15개**를 재대조했다. **11개 원본은 현재 경로에 없다.** 기존 통합본의 원문 위치·해시·요구사항 ID를 참조하며 파일 부재를 완료 증거로 삼지 않는다.
- 6월 5일 지시서 두 개도 정확한 경로에서 다시 읽기를 시도했으나 현재 `PermissionError`였다. [접근 결과](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/june-source-access.json)에 보존했다. 이 두 문서는 본문 재대조 미완으로 남기며 ACL 변경·추측 복원·삭제를 하지 않는다.
- 구 통합본의 **435개 고유 요구사항 ID**, 기존 **50개 finding**, 별도 **100개 감사 항목**의 교차표를 보존했다. 서로 중첩되므로 이 숫자를 더해 결함 수로 표시하지 않는다.
- 100개 감사 항목의 출발점은 **44개 판정 자료 있음 / 29개 부분 판정·정책 또는 추가 증거 필요 / 27개 미판정**이었다. 후속 대조에서 미판정 27개 모두에 현재 source/acceptance 판정을 추가했다. 이 가운데 전체 기준 **9개 행은 이전 기록 이후 소스 해시가 바뀌었다.** 과거 판정·현재 source 대조·실제 테스트 통과를 각각 보존하며 어느 수도 완료 건수로 바꾸지 않는다.
- 과거 50개 finding도 **50개 전부** 현재 owner/회귀 계약 또는 잘못된 전제와 대조했다. 구현 존재 21, 잔여 소스 후보 7, 부분 반영 7, 검증 보완 2, 계약 필요 10, 잘못된 전제 1, 포괄 변경 제외 2다. 행별 anchor·현재 SHA·다음 검증은 [추가 대조 기록](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/supplemental-review.json)에 있다.

검색·동일성 기준은 [DirectiveInventory](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/directive-inventory.json), [원본 계보](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/historical-lineage.json), [435개 요구사항 교차표](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/inherited-requirement-crosswalk.json)에 있다. 재사용 prompt와 봉인된 canary는 병합·retirement 대상이 아니다.

**범위 한계:** Desktop에서 접근 가능한 저장소와 관련 작업 기록을 조사했다. 이 컴퓨터에 전달되지 않은 Notebook 로컬 파일, 모든 클라우드 대화의 전수 검색까지 완료했다고 주장하지 않는다. 현재 연결되지 않은 원본의 내용은 추측 복원하지 않는다.

## 2. 이번 검증으로 새 구현 지시에서 뺀 내용

| 항목 | 현재 근거 | 새 실행본 처리 |
|---|---|---|
| quota 부족의 같은 요청 재시도·fallback 차단 | `LlmGatewayFailureClassifier`, Responses adapter, `FallbackAwareChatModel`, `ChatWorkflow`에 구현. 선택한 다섯 P0 테스트 포함 집중 실행 통과 | 재구현 제외. Experiential/Astra 활성화와 분리 |
| WU-R70 blank query, topK 상한, gateway 시도 기록 | `WebSearchTool.java:46`에서 1..20 제한, `:54` blank 사전 종료. `AcmeAICoreGateway.java:185`부터 bounded 시도 기록. gateway/wiring 테스트 통과 | 8월 15일의 “미구현” 설명은 폐기. 이미 있는 경계 보존. 모든 실제 제공자 호출 의미를 인증한 것은 아님 |
| ChatWorkflow 요청 trace 봉투 추출 | `ChatWorkflowRequestTraceEnvelope.java` 및 해당 테스트 존재·통과 | CLEANUP-A 중복 추출 제외 |
| Next BFF 기본 전송·Spring chat stream | BFF test와 `chat_ui_stream_contract_tests.js` 현재 실행 통과 | 새 BFF/route/취소 프레임워크 생성 제외. 시각·런타임 동등성은 W10 |
| 최근 취소·HTTP 시작 관측 및 격리 JAR 검증 보강 | `verification/isolated-runtime-boundary-20260908`의 현재 파일 21개 postimage와 XML 49개 해시 일치. 당시 Java 465개와 JAR loopback 검증 기록 존재 | 같은 소스 보강 반복 제외. 이 재대조는 새 JAR 실행이 아님. 기존 PID의 과거 ADMIN snapshot 증거와 외부 제공자 wire는 별도 |
| 대화 자세·멀티모달 구현의 전면 부재 주장 | `ConversationFrameV1`, resolver, `PromptContext`, `StandardPromptBuilder`와 `ChatWorkflow.primaryUserMessage` 연결 및 관련 테스트 파일 존재 | 전체 구조 재작성 제외. 완전한 의미 수용 조건은 W12에 남김 |
| HTTP smoke v1 공개 URL 우선 지시 | v2가 local-boot/AssumeRunning 경계를 명시 | v1을 새 실행 기준에서 제외; W01은 v2만 사용 |
| 예전 통합 controller의 inventory mismatch | 2026-08-06의 불변 program/state와 현재 입력 집합이 다름 | 예전 controller를 새 program에 억지 적용하거나 범용화하지 않음 |

집중 Java 검증: **12 suites / 149 tests / failures 0 / errors 0 / skipped 0**. [실제 실행·XML 목록](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/focused-java-corrected/run.json).

후속 Java 검증: **14 suites / 117 tests / 116 통과 / failures 0 / errors 0 / skipped 1**. [추가 실행·XML 목록](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/supplemental-java/run.json). 건너뛴 사례는 `AttachmentServicePhysicalDeletionTest.storageDeleteRejectsLinkTraversalWithoutTouchingOutside`이며 Windows에서 symbolic-link fixture를 만들 수 없을 때의 assumption이다. 해당 링크 경계까지 통과했다고 하지 않는다. 두 실행 합계는 266개 발견, 265개 통과, 1개 건너뜀이며 전체 저장소 테스트 수가 아니다.

최초 명령은 존재하지 않는 `verifyLangchain4jVersionPurity`를 사용해 실패했다. 실패 기록을 보존하고 실제 등록명 `checkLangchain4jVersionPurity`로 정정한 다음 통과했다. 이 명령 오타를 애플리케이션 결함이나 테스트 RED로 세지 않는다.

## 3. 남은 실행 단위와 우선순위

| 순서 | 단위 | 현재 판정 | 바로 이어서 할 일 |
|---:|---|---|---|
| 1 | W01 로컬 smoke URL 경계 | **RED 재현 2건** | 두 기존 fixture에 동일 사례를 넣고 resolver/호출부만 수정 |
| 2 | W02 운영자 route OFF 우선순위 | **소스상 우회 경로 확인; HTTP-count RED 필요** | direct OFF + manager unavailable + fallback 후보 한 사례 |
| 3 | W03 GPU READY와 manager OFF 수명 | **증거 없이 true 반환하는 경로 확인** | ps 실패·불일치·누락을 fake runtime으로 검증 |
| 4 | W04 창작 난수 계약 | **이번 통합에서 8회 유지 결정** | 후속 좌표 분리 설계와 현재 회귀 계약을 유지; 2회 복귀 지시 제외 |
| 5 | W05 동적 프롬프트 조립 | **선언한 구현 미발견** | 기존 PromptBuilder 경계의 disabled no-op RED부터 |
| 6 | W06 에이전트 코드 증거 gate | **전용 gate 미발견; health loop 존재** | 기존 loop를 재사용하는 누락·stale 증거 fixture부터 |
| 7 | W07 위험·효용 shadow 기록 | **요청한 계약 미발견** | 정책 불변 + bounded count-only 기록 계약부터 |
| 8 | W08 RAG 조화도 목표 | **현재 corpus 측정 없음** | 고정 corpus·seed·분모·결측 정책으로 baseline |
| 9 | W09 과거 50/100 잔여 목록 | **50개 및 기존 미판정 27개 현재 대조 완료; 잔여 계약 명시** | 아래 신뢰 경계·집계·복구 사례 중 정확한 항목 하나만 반증 |
| 10 | W10 Next/기존 PID 런타임 증거 | **정적·fixture와 운영 증거 분리 필요** | 기존 기록을 요청·런타임 identity별로 대조 |
| 11 | W11 Experiential/Astra·외부 capability | **공식 계약·접근·승인 증거 필요** | 외부 호출 없이 정확한 계약과 활성화 범위부터 |
| 12 | W12 대화 자세·멀티모달 의미 검증 | **기존 3개 합성 회귀 suite 통과** | 그 범위의 재작성 제외; 전체 워크플로 memory/보조 호출과 실제 비전 의미 수용은 분리 |

이 순서는 모든 단위를 한 source lease로 묶으라는 뜻이 아니다. W01의 script/test 경계와 W02의 Java 경계도 별개다. 원인 하나가 끝나면 다음 단위를 선택하고 최신 preimage를 다시 확보한다.

## 4. W01 — 두 로컬 smoke resolver의 남은 v2 반영

근거 원문: `__patch_drop__/notebook/http-rollback-local-smoke-supabase-boundary-desktop-directive-v2.md`.

**현재 RED:** `scripts/smoke_chat_debug_fx_sse.ps1:266`과 `scripts/trace_memory_recovery_synthetic_smoke.ps1:65`의 실제 `Resolve-SmokeBaseUrl`만 AST에서 선택해 실행했다. 상속 공개 URL이 있고 local boot인 사례에서 두 함수 모두 loopback 대신 상속 값을 선택했다. explicit loopback과 빈 환경 대조군은 각각 통과했다. 네트워크·boot 본문은 실행하지 않았다. [RED 결과](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/smoke-resolver-red.json).

- 목표 파일: 위 두 script와 각각의 기존 `_tests.ps1` 파일.
- 참조 구현: `smoke_websoak_kpi_provider_disabled.ps1:98`, `smoke_chat_debug_events_readback.ps1:187`. 현재 이 두 파일은 같은 결함의 수정 대상에서 제외한다.
- 최소 변화: local boot에서는 상속 공개 URL을 사용하지 않는다. `AssumeRunning` 여부를 resolver와 호출부에 전달한다. explicit BaseUrl의 우선순위는 유지하되 local-start이면 HTTP·loopback·실제 시작 포트가 일치해야 한다. 환경은 기존 `finally`에서 복원한다.
- GREEN: 상속 공개 URL + local boot는 loopback, AssumeRunning은 허용된 기존 URL, explicit 값 우선, local-start의 HTTPS/remote/포트 불일치 거부, 정상 대조군 유지. private 환경값은 출력하지 않는다.
- 검증: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke_chat_debug_fx_sse_tests.ps1`, `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/trace_memory_recovery_synthetic_smoke_tests.ps1`.
- 현재 기존 두 정적 검사는 모두 통과한다. 따라서 **기존 GREEN만 재실행해서 이 결함을 완료 처리하면 안 된다.** 새 failing fixture가 정확히 이 분기를 실행해야 한다.
- 롤백: 선언된 두 함수·호출부와 해당 fixture의 이번 hunk만 되돌린다. 도메인·TLS·보안 설정 변경, 서비스 재시작은 이 단위에 포함하지 않는다.

## 5. W02/W03 — 운영자 OFF와 검증 후 GPU 복귀

기준 원문은 [9월 8일 수동 OFF 지시서](../data/agent-handoff/codex/report/20260908-rtx3090-manual-disable-source-directive.md)다. 8월 24일 intermittent-resilience의 이미 있는 endpoint health/fallback 체계를 다시 만들지 않는다. 원문의 T01..T14와 D1..D4를 모두 보존하고 아래 경계로 나눠 실행한다.

### W02: route OFF를 fallback·생성·상위 retry보다 먼저

- 현재 `LlmRouterAspect.routeWithGateway:200`은 manager availability와 fallback을 먼저 검사하고, route disabled는 `buildRoutedModel:680`에서 검사한다. OFF 요청이 앞쪽 fallback으로 넘어갈 수 있는 소스 경로가 남았다.
- 첫 RED는 `LlmRouterGatewaySecurityTest`의 direct-disabled 사례에 manager unavailable + 적격 fallback을 결합한다. 기대: `DISABLED / route_disabled`, primary/fallback HTTP **모두 0**. 이번 조사에서 이 통합 RED는 실행하지 않았으므로 재현 완료라고 표시하지 않는다.
- D1을 최소 수정한 뒤 D2/D3를 별도 하위 단위로 진행한다. 정확한 대상은 `LlmRouterAspect`, `DynamicChatModelFactory`, `LlmConfig`, `ChatWorkflow`와 기존 원문의 테스트 파일이다. `LlmRouterProperties`는 기존 enabled 해석 재사용이 필요한 범위만 허용한다.
- plain model, alias, self-call, Spring bean, rebuild 예외, strict true/false, outer retry까지 OFF를 보존한다. 이미 구현된 quota non-replayable을 과잉 확장해 모든 GPU 장애를 영구 금지하지 않는다.
- auto에서는 disabled 후보만 제외한다. 직접 OFF 요청을 다른 모델·3060·cloud로 바꾸지 않는다. 명시 route와 실제 model/endpoint/device-role 매핑을 함께 검사하며 포트만으로 GPU를 단정하지 않는다.
- 첫 명령: `gradlew.bat test --tests ai.abandonware.nova.orch.aop.LlmRouterGatewaySecurityTest`를 아래 격리 설정으로 실행한다.

### W03: manager와 GPU READY의 증거 경계

- `LocalLlmProcessManager:789-805`는 `/api/ps` IOException, 모델 불일치, VRAM 필드 누락에서 현재 true에 도달할 수 있다. 이 부분은 source-backed gap이며 실제 장치 장애 주입은 필요 없다.
- 목표: manager가 소유한 OFF model/endpoint에 launch/warmup/recovery 0회. 별도 3060 manager는 기존 정책대로 동작한다. 외부 소유 프로세스는 건드리지 않는다.
- GPU pin이 요구된 요청에서는 실제 model running과 GPU 할당 증거가 없으면 GPU READY로 승격하지 않는다. 명시 pin·상속 pin을 모두 검증한다. `size_vram > 0`만으로 3090 귀속을 인증하지 않는다.
- 기존 CPU 허용 범위, cooldown, half-open, restart 상한을 유지한다. 수동 ON과 CPU 성공은 GPU 복구 증거가 아니다.
- 기존 `LocalLlmRecoveryTest`, `LocalLlmProcessManagerTest`, `OllamaNativeChatModelTest`의 fake clock/process runtime으로 T07..T13을 추가한다. T14 실제 복구는 별도 장치·요청 identity 증거가 있을 때만 수행한다.
- 기존 YAML/env 이름, model·embedding·credential 값, 새 관리자 API·UI, 시스템 환경변수, 장치 상태 변경은 이번 지시서의 자동 실행 범위가 아니다.
- 롤백: W02와 W03 각각에서 취득한 preimage의 해당 hunk만 복원한다. 원래 설정 복원은 별도 허가된 운영 단계이며 무조건 true로 설정하지 않는다.

## 6. W04 — 최신 독립 좌표 계약을 유지

원문: `data/agent-handoff/notebook/creative-emergence-desktop-superpowers-20260816.md`.

현재 VIVID/WILD/FERAL 가중치·밴드·2자리 정규화·민감 주제 억제 및 요청 계획 전달이 있다. `StochasticParamSampler.java:73-102`는 selector 1회 + 필드별 7회, 총 8회 추출한다. `StochasticParamSamplerTest.java:107`, `:182`가 그 계약을 명시하고 이번 집중 실행에서도 통과했다. 원문의 selector 1회 + 공유 jitter 1회와 동등하지 않다.

**이번 통합 결정은 8회 유지다.** 사용자가 완료·부족 여부를 판단해 지시서를 보완하도록 위임한 범위에서 새 지시서의 기준을 정했다. `docs/superpowers/specs/2026-08-29-selection-entropy-replay-design.md:8`은 1–5절 승인 상태를 기록하고, `:687`의 sampler 계약은 공통 entropy API와 안정적인 actor key를 요구한다. 후속 plan의 `2026-08-29-selection-entropy-replay.md:1902`는 profile component의 독립 좌표를 지정한다. 현재의 selector + 7개 필드 계약과 함께 보면 8회 방식 유지가 후속 설계에 맞는다. 과거 문서를 명시적으로 폐기한 승인 문구 자체는 발견되지 않았으므로 그 역사적 사실을 꾸미지 않는다. 이 판단은 새 지시서의 우선순위 결정이며 애플리케이션의 난수 동작을 변경하지 않는다.

원문/공식 검색 anchor 보존도 실제 소비 owner에 존재한다. `SearchPolicyEngine.java:130`이 원문을 먼저, 공식 자료 검색 anchor를 다음으로 예약하고 `:154`에서 창작 변형을 덧붙인다. `SearchPolicyEngineRedactionTest.java:76`, `:118`은 그 순서와 one-token 사례를 검사한다. 이 회귀 파일은 읽기 대조이며 이번 실행에 포함하지 않았다. 따라서 기존 anchor 구현 재작성은 제외하고 해당 회귀를 보존한다. 실패 시 기존 non-creative 요청 동작, shared policy·runtime memory·provider 설정은 유지한다.

## 7. W05/W06/W07 — 오래된 통합본에서 빠뜨리면 안 되는 미구현

세 가족 모두 8월 6일 통합본에 요구사항이 남아 있다. 최근의 8월 15일 축약 목록에 없다는 이유로 폐기하지 않는다. 435개 교차표의 가족별 ID를 참조한다. 예전 controller의 WU-A0 실행 성공을 새 문서의 필수 조건으로 삼지 않고, 실제 의존 행동과 현재 검증을 확인한다.

### W05: ND-DPA / 기존 WU-P100

- `PromptAssemblyProperties/Catalog/Planner/Decision/Trace`, `main/resources/prompts/assembly/prompt-assembly.v1.yaml`이 현재 없고 exact alternate-owner 검색에서도 발견되지 않았다.
- 요구: 버전이 있는 fragment 선택을 기존 `PromptBuilder.build(PromptContext)` 안에서 결정론적으로 수행한다. default disabled이며 비활성 시 최종 prompt 바이트·호출 수·메모리 쓰기 수가 기존과 같아야 한다.
- 기존 WU-E10 ensemble/final-release 경계를 먼저 현재 테스트로 특성화한다. 새로운 prompt 경로·대체 final builder·두 번째 memory 저장소를 만들지 않는다.
- 첫 RED: disabled no-op, 동일 입력의 선택·해시 재현성, 잘못된 manifest의 fail-soft, bounded lineage. 아직 `PromptAssembly*` 테스트가 없으므로 wildcard 실행 실패를 실제 behavioral RED로 세지 않는다.
- 대상·제외·원문 의미는 구 통합본 ND-DPA-001 이하와 WU-P100에 고정한다. 한 번에 모든 orchestration을 변경하지 않는다. 롤백은 추가 fragment/assembly hunk만.

### W06: ND-AGENT-GATE / 기존 WU-G80

- `source_health_validation_loop.py`와 `sourceHealthValidationLoop`는 있다. 전용 `agent_code_evidence_gate.py`, 테스트, skill, task wiring은 없다.
- 먼저 기존 loop와 이번 저장소의 `run_verified_command.py`가 제공하는 실행 ID·종료 코드·선택 XML·신선도 기능을 대조해 재사용한다. 파일명 부재만으로 두 번째 실행기를 만들지 않는다.
- 아직 필요한 계약: 증거 없음/FAIL/PASS의 구분, immutable fixture, hidden oracle 분리, 잘못된·stale XML의 실패, source/provider/DB mutation count 0, allowlisted 결과.
- 첫 RED는 독립 임시 fixture에서 증거 누락과 stale 성공 기록을 넣었을 때 PASS가 거부되는지다. 실제 앱·외부 agent 호출을 전제로 하지 않는다.
- 범위는 기존 validation loop와 필요한 최소 gate/test/schema다. 최초 원본은 현재 없으므로 기존 통합본의 ND-AGENT-GATE 원문을 유지하고 누락된 세부 정책을 추측하지 않는다.

### W07: ND-RISK-UTILITY / 기존 WU-U90

- `RagFailureBlackboxService`의 일반 SHADOW_REVIEW·quarantine·uncalibrated 기록은 존재하지만 요청한 위험·효용 shadow count 계약은 발견되지 않았다. 같은 단어가 있다는 이유로 완료 처리하지 않는다.
- 기존 owner와 해당 테스트만 시작 범위다. source-backed count-only 측정을 추가하더라도 현재 risk 판단·routing·학습 promotion·원문 저장 정책은 변하지 않아야 한다.
- 첫 RED: enabled/disabled에서 policy decision 동일, 실패 시 기존 서비스 결과 동일, bounded retention·허용 키·count-only. raw query/response, 원문 memory, 민감 값은 기록하지 않는다.
- `RagFailureBlackboxServiceTest` 전체 통과만으로 새 shadow 계약을 검증했다고 하지 않는다. 새 의무를 구체적으로 검사하는 사례와 scope를 보고한다.

## 8. W08 — RAG 조화도 80은 아직 측정 목표

기준 원문: `data/agent-handoff/notebook/2026-08-18-rag-harmony-80-superpowers-desktop.md`.

`NormalizedRagMetrics.balancedScore`와 `HarmonyScoreEngine`은 서로 다른 지표다. `fitScore = 100 * balancedScore`, faithfulness, 위험, evidence coverage, 반례 비율, promotionAllowed를 각각 기록한다. 149개 집중 테스트에 metric/engine 테스트가 포함됐지만 실제 corpus의 80점 달성을 뜻하지 않는다.

현재 `HarmonyScoreEngine.java:52`는 미완료 break가 있으면 score 0, 모두 완료면 100+bonus로 동작한다. 진단용 achievementPct 70–80 표현이 실제 요구와 충돌한다는 고정 fixture RED가 나올 경우에만 그 진단 계산을 별도 수정한다. promotion fail-closed와 HB 조건은 완화하지 않는다. 원문의 약 79.66 예시는 실측치가 아니다.

첫 작업: corpus·seed·sample count·결측 정책·분모를 고정하고 기존 metric 경계에서 baseline을 산출한다. 목표치에 맞추기 위한 score 보정이나 새 알고리즘을 추가하지 않는다. 웹·모델·브라우저 증거가 필요하면 요청 상한과 runtime identity를 먼저 선언한다.

## 9. W09 — 과거 50/100 항목의 남은 의미 검증

[50개 교차표](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/historical-50-crosswalk.json)에는 원문의 번호·현재 참조 위치와 당시 판정을 보존한다. `PATCH_READY`, `VALID_BUT_PREIMAGE_CHANGED`는 8월의 판단이며 현재 실행 권한이 아니다. 특히 이미 처리한 upload prefix, cache/executor/interrupt 계열은 최신 source/test와 대조해 중복 수정하지 않는다.

처음 대조한 PATCH_READY 7개에 이어 나머지를 포함한 **50개 전부**를 대조했다. 21개는 구현이 존재해 같은 수정 지시를 제외한다. 익명 owner-key 통일, 첨부 owner admission·실제 파일 삭제·TTL cleanup, N8n bounded body/idempotency, job TTL/capacity·bounded executor/shutdown, location consent 경계, keyword prompt UTF-8, BrainState executor, finite SSE lifecycle 등이 여기에 포함된다. 기존 회귀 파일 존재와 이번 실행 통과는 [추가 대조 기록](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/supplemental-review.json)의 `freshRegressions`로 구분한다. 파일명 부재나 과거 dirty 표시만으로 새 결함을 만들지 않는다.

| 50개 목록의 잔여 가족 | 현재 확인한 최소 경계 | 첫 후속 검증과 제외할 중복 작업 |
|---|---|---|
| F18/19/20/26 오류 문구·sentinel | `ChatWorkflow:3652/3659/8796`, `SmartFallbackService:67`, `PageController:342`에 손상된 literal 또는 다른 sentinel이 남음 | 두 단계 evidence rescue 실패와 generic model-setting exception을 합성 주입. 현재 `NoEvidenceChatFallback` 정상 경로를 다시 만들지 않고 typed reason/표시 경계 한 곳부터 특성화 |
| F38 검색 executor 포화 | `SearchExecutorConfig:185`의 `CallerRunsPolicy` | queue/worker를 로컬 barrier로 포화시켜 호출 스레드에서 I/O가 실행되는지 RED. 보상 정책을 고르기 전 existing caller timeout 계약 유지 |
| F43/F45 cache 수명 | `EmbeddingCache:144/212`, `PolicyBasedModelRouter:77/421/437`의 상한 없는 map | 유한 합성 키·시간 fixture로 retention 상한 검사. single-flight/interrupt/stale fallback 보존, client close 가능성·소유권을 추측하지 않음 |
| F27–33 timeout/취소 | Nova·Hybrid·Graph·Federated의 absolute deadline/managed executor/interruption은 현재 존재, 일부 `cancel(false)` 유지 | common-pool/순차 full-timeout 수정 재작성 제외. 실제 backing I/O가 끝나는지는 controlled blocking operation의 시작/종료·추가 admission으로 별도 검증 |
| F25/F42 검증 보완 | machine error code와 `QueryExpander` opaque bounded TTL 구현 존재 | error code와 표시문을 분리해서 검증. cache는 deterministic expiry/expired 제거·동일 키 concurrency 사례만 보완 |
| F05/09/12/15/17/21/22/36/41/47 계약 필요 | sessionless UX, 파일 형식 정책, N8n dispatcher/callback, consent deletion, 정확 문구, transient memory, 실제 ONNX I/O, app fusion ABI | 교차표의 한 가지 누락 계약만 확보. 단순히 disabled ONNX·활성 `java_clean`·원본 없는 문구를 고치기 위한 source 작업을 시작하지 않음 |

[100개 재대조표](../data/agent-handoff/codex/report/notebook-directive-refresh-20260908/historical-100-reconciliation.json)는 이전 판정·해시를 보존한다. **기존 미판정 27개는 이제 현재 source 대조가 있다:** 잔여 소스 후보 7, 계약 필요 7, 수용 검증 필요 9, 의도된 정책 3, 구현 존재 1이다. 자료가 있던 44행이나 부분 판정 29행까지 새 semantic PASS로 승격한 것은 아니다. 소스 해시가 달라진 9행의 기존 결과도 계속 이전 증거로 표시한다.

| 후속 순서 | 100개 목록의 정확 ID/owner | 원래 요구를 보완한 RED·완료 조건 |
|---:|---|---|
| 1 | AL-03 `VectorStoreService:514/569/652` | optional DLQ 없음·DLQ 실패에서 미검증 AutoLearn 내용이 primary queue로 들어가는지 합성 metadata로 확인. quarantine metadata 존재만으로 격리 성공을 주장하지 않음 |
| 2 | API-ARCHIVE-05 `ConversationNoiseClassifier:59`, `ConversationTopicTimelineBuilder:113` | 임의 링크/요약 모양의 비신뢰 텍스트가 `verified=true` KB가 되는 반례. 현 테스트도 true를 기대하므로 그 기존 기대를 전체 진실성 증거로 삼지 않음. provenance에 근거한 검증 여부를 확정한 후 좁은 경계 수정 |
| 3 | AL-02 `TrainRagIngestService:274/436` | accepted/quarantine/rejected 합성 3종의 staged count와 accepted-training count를 분리. flush 성공만으로 accepted가 증가하는 사례를 먼저 실패로 고정 |
| 4 | AL-04/05 같은 ingest owner `:1069/1079/1023` | 같은 경로의 파일 내용 교체, vector flush 성공 후 checkpoint 저장 실패와 재시작. path hash의 비밀 보호는 유지하면서 content identity·idempotent replay 계약 검증 |
| 5 | AL-10 `Neo4jKgChunkWriter:65/73/86` | chunk/entity/relation의 별도 transaction 중간 실패를 fake session으로 재현. 청크당 원자성 또는 명시한 보상 계약 검증. live Neo4j 쓰기 없이 시작 |
| 6 | STKG-09 `CfvmSnapshotService:69/94` | 주기적 append의 bounded retention과 latest restore 불변식 fixture. 보존 기간을 먼저 고정하고 과거 DB snapshot 삭제 권한을 추정하지 않음 |

AL-06 handoff writer의 동시 writer/두 프로세스·atomic move/append readback 보강은 현재 존재하므로 재작성에서 제외한다. TEST-01/FQCN-01/AL-12의 app test-empty·선택적 duplicate 정책·reinforcement OFF는 의도된 현재 정책으로 보존한다. AL-01/07, STKG-01/08은 유효 설정·PII 범주·삭제 전파·DB schema owner 계약이 남고, AUTO/BUILD/APP/GATE/RC/TBL/SEC 항목은 교차표에 정확한 consumer/servlet/profile/corpus/wire acceptance를 남겼다. AL-07에서 secret masking 테스트 통과를 모든 PII 제거로 확대하지 않는다.

이전 해시와 다른 9행도 마지막에 현재 owner/test에서 다시 읽었다. SEC-03의 official-only 전량 거절 결과 복원, PM-05의 native blank 응답, PM-07의 ENFORCE fallback eligibility는 현재 보강이 남아 있어 재구현 제외다. PM-06은 **strict primary 시도 상한**과 모든 nested fallback 금지를 구분한다. RC-02의 Chat/RAG 동일 결과 요구는 별도 계약 없이는 만들지 않고, SEC-01은 outbound query의 allow/redact/block 정책이 필요하다. API-TRACE-04의 durable fallback projection과 STKG-03의 domain/name 복합 유일성은 source/합성 회귀가 존재하지만 live DB restart·deployed migration까지 증명하지 않는다. TBL-04는 기존 도구의 적대적 fixture 검증만 남긴다. 9개 중 TBL-04는 위 27개와 중첩되므로 36개를 별도 조사했다고 합산하지 않는다.

AutoLearn 구현을 실제 시작할 때는 기존 규칙대로 `data/agent-handoff/codex/manifest.json` → `cycles.jsonl` → `rejected.jsonl` 순서로 redacted intake를 확인한다. `accepted.jsonl`은 보조 자료이며 `train_rag.jsonl` 원문을 덮어쓰지 않는다. 위 RED는 synthetic 파일·fake vector/DB로 만들고, rollback은 해당 source/test hunk에 한정한다. 원본 학습·checkpoint·DB 데이터를 시험 편의를 위해 지우지 않는다.

후속 실행은 정확한 ID 하나를 선택하고 교차표의 nextVerification을 현재 caller/owner에 맞춰 특성화한다. 숫자 100을 채우기 위한 무작위 결함 추가, 110개 broad catch 일괄 변경, giant-class 일괄 분해는 하지 않는다. 활성 `java_clean` 일괄 삭제, owner 없는 WeightedPowerMean 재구현, 원문 근거 없는 mojibake 복원도 제외한다.

## 10. W10/W11/W12 — 검증·외부·정책 경계

- **W10:** Next는 실제 JS App Router다. 과거 `.ts` 요구만으로 TypeScript 전환을 새 작업에 넣지 않는다. BFF/Spring의 request/session ID, stream chunk 경계, cancel, restore, evidence UI는 현재 fixture를 재사용하고 실제 runtime 비교가 필요한 때만 Browser를 사용한다. 기존 PID의 과거 snapshot ADMIN 접근은 새 loopback JAR 검증으로 대체할 수 없고, 로그인해도 과거에 기록하지 않은 호출 시작 시각은 복원되지 않는다. 동일 blocker에서 추가 생성 요청을 반복하지 않는다.
- **W11:** Experiential/Astra 원문의 P1/P2는 provider host·credential/property·entitlement·sampling·catalog 계약이 필요하다. P0 quota 보호가 GREEN이라는 이유로 활성화하지 않는다. 공식 문서를 확인하지 않은 모델·무료 과금·출시 상태를 이 문서가 확정하지 않는다. Supabase/Qwen/Mac/producer evidence는 관련 원문의 read-only 범위만 유지하며 현재 scope가 없는 외부 서비스가 로컬 단위 전체를 막지 않는다.
- **W12:** `ConversationFrameResolverTest`, `ChatWorkflowMultimodalPrimaryMessageTest`, `LangChain4j101MultimodalRequestSerializationTest`를 이번 추가 실행에서 통과했다. resolver의 OFF/SHADOW/ENFORCE, negation·인용·번역·3인칭, primary UserMessage의 텍스트/이미지 경계와 직렬화는 기존 구현·회귀 보존 대상으로 바꾼다. 이것만으로 전체 workflow의 보조 호출·memory side effect가 모두 0이거나 실제 비전 제공자가 의미를 충족한다고 하지 않는다. 남은 acceptance는 정확한 요청에 대한 보조 호출/memory write 계수와 실제 비전 의미 검증이다. private 대화·이미지 원문은 fixture에 복사하지 않는다.

## 11. 공통 실행·검증·중단 규칙

1. 단위 하나와 상대경로 target/test 목록을 선택한다. 현재 instruction, branch/HEAD, Git tracking, preimage, active caller, PatchDrop pending, 실제 writer/lease를 확인한다. 현재 index lock은 그대로 보존돼 있다. dirty tree만으로 전체 HOLD하지 않는다.
2. 애플리케이션 source edit은 기존 `demo1-source-edit-three-way-preflight`의 POSITIVE_QUERY/NEGATIVE_QUERY/NEUTRAL_QUERY와 순서 안정 APPLY, 기존 source-owner lease, 즉시 preimage/CAS를 거친다. docs/read-only/test-only에는 불필요한 source gate를 적용하지 않는다.
3. source edit과 index/ref 쓰기, build, runtime은 각각 판정한다. unknown index lock을 제거하거나 모든 읽기·검증을 중지하지 않는다. scoped-blocker-recovery의 현재 계약을 사용한다.
4. 지금 있는 failing test 또는 정확한 새 behavioral RED를 먼저 확보한다. 파일 미존재, 오타 명령, wildcard 0개 테스트는 behavioral RED가 아니다.
5. 빌드는 `gradlew.bat`, source 출력과 project cache를 host/task별로 격리한다. 로컬 fixture 검증은 외부 요청 0, DB 요청 0을 기본 상한으로 한다. 이번 집중 검증은 600초 상한으로 종료했고 실제로 통과했다. 미래 runtime은 별도 시간·요청·프로세스 소유 상한을 정한다.

```powershell
# 이후 실행자가 그 작업의 별도 세션에서 사용할 격리 예시
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-notebook-remaining'
$pcd = Join-Path $env:LOCALAPPDATA 'awx-gradle-project-cache\desktop-notebook-remaining'
.\gradlew.bat checkSourceSetHygiene checkLangchain4jVersionPurity --offline --no-daemon --project-cache-dir $pcd
# 선택한 실제 테스트 FQCN만 --tests에 지정한다.
```

6. 필요한 경계에서만 compileJava → focused tests → :app:classes → bootJar → 소유 runtime/browser proof로 넓힌다. 이번 문서 검증에서 새 bootJar/runtime/browser를 실행했다고 표시하지 않는다.
7. source postimage, task-only diff, 선택 XML·종료 코드·run ID·artifact 해시, count-only secret scan을 남긴다. 모델 delegate 완료·HTTP 200·문서 전달·해시는 semantic/wire 성공으로 승격하지 않는다.
8. `openssl`·`opnessl`의 이름·값·구조, credential 흐름, PromptBuilder, 현재 의존성 버전, 사용자 hunk를 보존한다. 새로운 production dependency, DB/ACL/credential/Git commit·push·deploy·프로세스 종료에는 별도 operation authority가 필요하다.
9. HOLD는 `holdScope`, `firstBlockingRule`, `blockingEvidence`, `independentWorkCompleted`, `repositoryWideHold`로 기록한다. 이 문서의 현재 HOLD는 개별 구현/계약/외부 증거에만 적용된다.

## 12. 전달·retirement·태그 적용

이 문서가 이번 요청의 하나의 정식 실행본이다. 기존 문서와 불변 controller/state를 수정하거나 삭제하지 않았다. “완료된 내용은 빼라”는 요청은 **새 실행 목록에서 중복 지시를 제외**하는 것으로 적용했다. 원본의 실제 삭제·이동·retirement는 수행하지 않는다. canonical 파일의 최종 SHA-256은 companion 무결성 파일과 최종 응답으로 제공한다.

Superpowers는 증거·검증 절차를 지원하고, 이번 핵심 route는 `demo1-consolidating-notebook-directives`다. Deep Research는 원문 계보와 반증 대조, Data Analytics는 중복 없는 분모·상태 교차표에 한정했다. Visualize는 비교표로 표시한다. Computer/Browser는 현재 파일 대조와 문서 검토에 필요하지 않아 앱을 조작하지 않았다. Sites는 내부 코드 지시서의 게시 목적이 없어 배포하지 않았고, Plugin Management는 기존 callable 도구로 충분함을 확인했다. GLM은 key-presence true지만 CLI 0.144.1 / sol v2의 현재 전달 HOLD로 호출 0; 두 읽기 전용 Codex explorer를 사용했다.

문서 전달 완료와 구현 완료를 구별한다. **이 실행본의 남은 W01..W12를 모두 GREEN으로 표시하지 않는다.** 첫 후속 작업은 이미 재현된 W01을 기존 두 fixture로 옮겨 한 가지 원인의 좁은 수정으로 끝내는 것이다.
