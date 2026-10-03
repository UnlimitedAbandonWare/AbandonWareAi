# VERIFY_COMMANDS — mAWSain do01~do05 검증 명령표 (템플릿)

exitCode/observed 칸은 **실행한 사람이** 채운다. 미실행은 `NOT_RUN` — 빈 칸을 pass로 세지 않는다.
명령·FQCN은 live 트리에서 존재 확인된 것만 기입(사실). `[PROPOSED]`는 미존재 테스트/스크립트.

## 환경 전제

- Project Root `C:\AbandonWare\demo-1\demo-1\src`에서 실행. Java 17 + Gradle wrapper(`gradlew.bat`), Maven 없음.
- 테스트 소스셋: `test`(src/test/java) · `chatUiTest` · `gatewaySecurityTest` · `crossSubsystemContractTest` · `glmAgentMcpTest`.
- `test` 태스크는 `isolatedCustomTestClasses`(gateway/cross-subsystem 지정 목록)를 제외 — `build.gradle.kts:889–895`.

## C0 — 기본 레일 (변경 전후 공통)

| id | purpose | command | expected | exitCode | observed |
|---|---|---|---|---|---|
| C0-1 | 컴파일만 | `.\gradlew.bat :compileJava -x test` | exit 0 | | |
| C0-2 | 리소스 처리 | `.\gradlew.bat :processResources` | exit 0 | | |
| C0-3 | sourceSet 위생 | `.\gradlew.bat checkSourceSetHygiene` | exit 0 (active root 불일치 시 GradleException) | | |
| C0-4 | toolbox 파싱 | `python -B scripts/test_awx_mcp_toolbox.py` | exit 0 | | |
| C0-5 | toolbox 입력 파서 | `python -B scripts/test_awx_mcp_toolbox_input_json.py` | exit 0 | | |

## D1 — do01 (루트/도구 존재)

| id | purpose | command | expected | exitCode | observed |
|---|---|---|---|---|---|
| D1-1 | 기존 redaction 계약 유지 | `.\gradlew.bat test --tests com.abandonware.ai.agent.tool.impl.ops.RepoScanToolRedactionContractTest` | exit 0 | | |
| D1-2 | [PROPOSED] 루트 계약 | `.\gradlew.bat test --tests com.abandonware.ai.agent.tool.impl.ops.RepoScanToolRootContractTest` | exit 0 (build_root/overlay/모호/권한/한도 케이스) | | |
| D1-3 | [PROPOSED] 부작용 정책 | `.\gradlew.bat test --tests com.example.lms.ControlTowerEffectPolicyTest` | exit 0 | | |

## D2 — do02 (동일 요청 조회, 최우선)

| id | purpose | command | expected | exitCode | observed |
|---|---|---|---|---|---|
| D2-1 | HTTP page 계약 유지 | `.\gradlew.bat test --tests com.example.lms.api.DebugEventsPageTest` | exit 0 | | |
| D2-2 | SSE 재접속 유지 | `.\gradlew.bat test --tests com.example.lms.api.DebugEventsDiagnosticsControllerSseLifecycleTest` | exit 0 | | |
| D2-3 | 실패 신호 유지 | `.\gradlew.bat test --tests com.example.lms.api.DebugEventsDiagnosticsControllerFailureSignalTest` | exit 0 | | |
| D2-4 | [PROPOSED] 도구 correlation | `.\gradlew.bat test --tests com.abandonware.ai.agent.tool.impl.ops.DebugTraceLookupCorrelationTest` | A/B 혼합에서 A만, evicted/invalid 구분 | | |
| D2-5 | [PROPOSED] stored snapshot | `.\gradlew.bat test --tests com.abandonware.ai.agent.tool.impl.ops.TraceSnapshotToolStoredLookupTest` | 소유권 거부·전역 fallback 없음 | | |
| D2-6 | snapshot store 유지 | `.\gradlew.bat test --tests com.example.lms.trace.TraceSnapshotFilterTest --tests com.example.lms.trace.TraceSnapshotRedactionTest` | exit 0 | | |
| D2-7 | 소유권/pointer 유지 | `.\gradlew.bat test --tests com.example.lms.api.ChatTraceSnapshotPointerPersisterTest --tests com.example.lms.api.ChatTraceMetaMessageRestorerTest --tests com.example.lms.api.ChatTraceDurableDetailTest` | exit 0 | | |

## D3 — do03 (탐색/effective view)

| id | purpose | command | expected | exitCode | observed |
|---|---|---|---|---|---|
| D3-1 | 기존 source.map 유지 | `.\gradlew.bat test --tests com.abandonware.ai.agent.tool.impl.ops.SourceMapToolTest` | exit 0 | | |
| D3-2 | [PROPOSED] 250+ targeted | `.\gradlew.bat test --tests com.abandonware.ai.agent.tool.impl.ops.SourceMapToolTargetedLookupTest` | filter-before-limit·handler 일치 | | |
| D3-3 | [PROPOSED] effective view | `.\gradlew.bat test --tests com.abandonware.ai.agent.tool.impl.ops.ConfigInspectToolEffectiveViewTest` | true/false 구분·비밀 미노출 | | |
| D3-4 | ops wiring 유지 | `.\gradlew.bat test --tests com.example.lms.config.AgentToolOpsConfigContextTest` | exit 0 | | |

## D4 — do04 (DebugCopilot 명령/웹 근거)

| id | purpose | command | expected | exitCode | observed |
|---|---|---|---|---|---|
| D4-1 | copilot 기존 | `.\gradlew.bat test --tests com.example.lms.service.trace.DebugCopilotServiceTest --tests com.example.lms.service.trace.DebugCopilotTriadicAdjudicationTest` | exit 0 | | |
| D4-2 | [PROPOSED] 명령 렌더링 | `.\gradlew.bat test --tests com.example.lms.service.trace.DebugCopilotCommandAdviceTest` | PS/POSIX 구분·metachar 거부 | | |
| D4-3 | [PROPOSED] 웹 근거 정책 | `.\gradlew.bat test --tests com.example.lms.service.trace.OfficialEvidencePolicyTest` | 내부 host redirect 거부 등 | | |

## D5 — do05 (UI/ZIP 재현)

| id | purpose | command | expected | exitCode | observed |
|---|---|---|---|---|---|
| D5-1 | bundle/exporter 유지 | `.\gradlew.bat test --tests com.example.lms.trace.TraceSnapshotExporterTest --tests com.example.lms.api.ChatApiControllerTraceMetaTest` | exit 0 | | |
| D5-2 | 세션 상세 유지 | `.\gradlew.bat test --tests com.example.lms.api.ChatSessionTraceDetailTest` | exit 0 | | |
| D5-3 | diagnostics 보호 유지 | `.\gradlew.bat test --tests com.example.lms.api.TraceSnapshotsDiagnosticsSecurityIntegrationTest --tests com.example.lms.api.TraceSnapshotsDiagnosticsControllerTest` | exit 0 | | |
| D5-4 | chatUi 계열 | `.\gradlew.bat chatUiTest --tests com.example.lms.trace.TraceSnapshotStoreAgentVisibleHtmlFocusedTest --tests com.example.lms.trace.ChatTraceRestoreTest --tests com.example.lms.api.ChatApiControllerLocalLlmTraceContractTest` | exit 0 | | |
| D5-5 | [PROPOSED] ZIP reader | `.\gradlew.bat test --tests com.example.lms.AnswerTraceBundleReaderTest` | traversal/중복/한도 거부 | | |
| D5-6 | [PROPOSED] probe 정책 | `.\gradlew.bat test --tests com.example.lms.PassiveProbeRoutePolicyTest` | self-probe 등 자동 순회 0 | | |
| D5-7 | [PROPOSED] browser | `npm run test:agent-debug -- --project=chromium` | **현재 미등록 스크립트 — 실행 전 harness 생성 필요; 지금은 NOT_RUN** | | |

## 내부 도구 경로 (참고 — 별도 보안 계약)

| id | purpose | command | expected | exitCode | observed |
|---|---|---|---|---|---|
| T-1 | internal tool 보안 | `.\gradlew.bat test --tests com.example.lms.api.internal.InternalAgentToolControllerSecurityTest` | exit 0 | | |
| T-2 | internal probe | `.\gradlew.bat test --tests com.example.lms.api.internal.InternalAgentToolProbeRoundTest` | exit 0 | | |

## 기록 규칙

- `exitCode`는 실제 `$LASTEXITCODE`. 핵심 assertion·artifact 경로를 `observed`에.
- baseline 실패와 신규 회귀를 분리해 기록. 컴파일 실패는 목표 RED로 세지 않는다.
- 유료/운영 접근 필요 검증은 허가 범위 밖이면 `NOT_RUN` 사유를 적는다.
