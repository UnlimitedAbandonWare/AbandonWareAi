# FAILURE_LIST — madasin do09 잔여 분류 (non-admin only)

Source: `data/agent-handoff/codex-autonomy/madasin-recovery-continue-d75f6b20/baseline-failures.json`
(121개 개별 실패 레코드 = 전체 `test` 116 + `chatUiTest` 6 중 parameterized 중복 포함; 수집 시점 2026-09-26T22:22Z)

원격/CI 비근거: local HEAD `847d3238`, remote advertised `b2eaba46`, 로컬 `origin/main` 부재 → merge-base 산출 불가 → 관계 불명.

작업 병행 참고: `codex-madasin-continue` 세션이 동일 실패 집합을 순차 수리 중(07:23~ 진행). 아래 `codex` 표기는 그 세션이 검증 green으로 돌린 그룹, `in-flight`는 현재 target manifest/Gradle 프로세스로 확인된 작업 중인 그룹.

| Label | 의미 |
|---|---|
| REGRESSION_FROM_MADASIN | madasin diff가 유발한 실제 변경/결함 |
| STALE_FIXTURE | 행위 보존, 계약 리터럴/시그니처만 drift (테스트 갱신 정당) |
| EMF_INFRA | 테스트 컨텍스트 인프라(EntityManagerFactory fixture 등), 제품 코드 아님 |
| UNRELATED | madasin 접촉 범위 밖 서브시스템의 기존 drift (2026-09-24 시점 이미 ~113 실패 기록) |
| UNKNOWN | 현재 증거로 판별 불가 — 실행/로그 재수집 필요 |

## 그룹별 분류

### EMF_INFRA — codex green (15)
| suite | tests | evidence |
|---|---|---|
| ChatApiControllerExtraQuotaTest | 8 | BeanCreationException in manual context; `red-emf`→`green-emf` cycle exit 0 (fixture EMF 등록으로 16/16 PASS, 프로덕션 변경 없음) |
| ChatSessionAccessGuardInteractionPolicyTest | 7 | 동일 |

### STALE_FIXTURE — codex verified green (5)
| suite | tests | evidence |
|---|---|---|
| ChatApiControllerInputGuardTest | 1 | ArgumentsAreDifferent — `appendMessage`→`appendMessageReturningId` mock drift; `verify-user-turn` exit 0 |
| ChatApiControllerSyncLifecycleTest | 3 | NeverWantedButInvoked/ArgumentsAreDifferent; 동일 cycle green |

### chatUiTest — codex verified (4)
| suite | tests | evidence |
|---|---|---|
| ChatWaitWorkflowFocusedTest | 1 | `verify-terminal` exit 0 |
| ChatPlanBudgetFocusedTest | 1 | `verify-ui-current` exit 0, BUT `verify-ui-fixtures` 이후 재실패 → FLAKY/in-progress |
| InterviewDemoFocusedTest | 1 | 동일 |
| ChatFrontendFallbackEvidenceFocusedTest | 1 | 동일 |

### STALE_FIXTURE — devin patched + verified green (18)
| suite | tests | basis |
|---|---|---|
| ChatApiControllerTraceMetaTest | 1 (`selectionEntropyStreamStartsOnlyAfterAttachReturnAndAcknowledgementGate`) | anchor `appendMessage(session.getId(),"user",dto.getMessage())` → F04 renamed to `appendMessageReturningId` (x0→x4); ordering 보존 확인. verify-stale-fixtures run: suite 53/53 green |
| ApiStreamDiagnosticRedactionContractTest | 2 | `hashValue(ex.getMessage())` → `hashValue(String.valueOf(ex))`; `stableLabel` 2nd site → `ModelSelectionException.streamFailureCode(ex)` 의도 전환(L2862). suite 6/6 green |
| ChatWorkflowAgentVisibleDebugEvidenceTest | 5 | `recentHistory` anchor → `conversationContext.present()?` ternary(순서 보존); `verifyAnswer` prefix drift. suite 357/357 green |
| ChatWorkflowCancellationContractTest | 2 | `return ChatResult.of(...)` → `return finishEarlyResult(ChatResult.of(...))` 래핑 anchor drift. suite 6/6 green |
| RequestTraceTest | 1 (`featureStatusHonestAboutUnwiredPaths`) | `selfask=requested:exec_not_wired` → source now emits `planner_missing`(L658); `shadow=not_wired`·`fingerprint=not_observed_on_path`는 substring 일치 유지. suite 17/17 green |
| ChatWorkflowLlmTimeoutContractTest | 1 (`everyRetryAndEndpointFallbackRechecksTheLiveRequestDeadline`) | ordering assert `unwrapFastBail < completeFailure` → madasin terminal-exception short-circuit(`throw responseTerminal`)이 먼저 오도록 재배치됨; fastBail은 여전히 completeAbandoned 계열(의미 보존). assert를 terminal-branch 기준으로 갱신. suite 20/20 green |
| ChatRecoverySafePatchContractTest | 1 (`naverYamlUsesCanonicalBridgePlaceholders`) | `application.yml` `client-secret:` 만 따옴표 누락 — sibling `keys:`/`client-id:`와 동일하게 `"${NAVER_CLIENT_SECRET:}"`로 정규화(해석값 동일). suite 6/6 green |

### REGRESSION_FROM_MADASIN — devin patched + verified (1)
| suite | test | basis |
|---|---|---|
| ChatApiControllerTraceMetaTest | `chatApiControllerDoesNotUseExactEmptyCatchBlocks` | `catch (Throwable ignore) {}` at ChatApiController L3535 `stashStreamTraceSnapshot` — madasin trace-stash 신규 코드; `logSuppressed("stream.traceStash", e)` breadcrumb으로 수정(파일 내 stream.* 패턴 동일). suite 53/53 green에 포함 |

### STALE_FIXTURE — devin patched + verified (frontend, 6 of 11)
| suite | test | basis |
|---|---|---|
| ChatFrontendSecurityTest | `chatUiScriptVersionBumps...` | template now `chat.js?v=mgain-trace-20260926` — post-madasin bump; literal 갱신. verified green |
| ChatFrontendSecurityTest | `chatSearchDefaultsToDemandDrivenOff` | OFF option 라벨 `검색 끔`(한국어) — semantics 보존. verified green |
| ChatFrontendSecurityTest | `chatTemplateMountsSessionModeDiagnostics` | `aria-label="Session history and mode diagnostics"` → 현 템플릿 `내 대화 기록`(L48; 요소·aria-live 동일 존재). verified green |
| ChatFrontendSecurityTest | `operationalStabilityQuickPromptExposesActionLabelAndStableSelector` | aria-label 한국어화(`개념을 쉽게 설명하는 질문 작성`); `title` 속성 제거 → `data-q=` payload assert로 대체. verified green |
| ChatFrontendSecurityTest | `chatFrontendShowsIntegrityWarningForMojibakeAnswerText` | `looksLikeMojibake(cleanHtml)` → source는 `looksLikeMojibake(bubble.textContent \|\| "")`(L3139; 렌더된 텍스트 검사로 의도 변경). verified green |
| ChatFrontendSecurityTest | `calmPremiumOperatorConsoleKeepsBrightAccessibleHierarchy` | `assertFalse(css.contains("radial-gradient("))` 전역 스캔 → `.chat-empty-state`의 정상 장식 gradient를 오탐; body/topBar/wrapper 블록 단위로 scope 축소(의도 보존). verified green |

### in-flight — codex replay/router seam (10)
| suite | tests | note |
|---|---|---|
| NestedWebFallbackProxyCharacterizationTest | 6 | traced=true/false params; `FallbackAwareChatModel.java`/`FallbackAwareReplaySafetyTest`를 codex가 claim, 22:51Z 패치 + 재실행 중 |
| LlmRouterRequestTimelineTest | 3 | LlmGatewayException/LlmResponseTerminalException — fallback 경로; 동일 seam |
| LlmRouterHttpAttemptBudgetContractTest | 1 | `routeTimeoutMillis(Math.max(1_000L,...))` 리터럴은 존재 — 실패 지점 다른 assert 추정 |

### UNKNOWN — madasin 인접, 실행 재검증 필요 (8)
| suite | tests | hypothesis |
|---|---|---|
| ChatWorkflowRootGuardProfileTest | 5 | 공용 `workflow(...)` helper 실패(L143) — guard 계약 anchor drift or 행위 변경 |
| ChatWorkflowStrictSingleAttemptHttpIntegrationTest | 2 | WantedButNotInvoked — provider timeout이 `LlmResponseTerminalException`/`rethrowIfRequestBudgetExhausted` 경로로 먼저 분류되면 `recordModelFailure` mock 미도달 가능; 실행 증거 없이 방향 불확정 |
| ChatWorkflowS7PromptBoundaryContractTest | 1 | hybrid memory prompt-boundary 행위 |
| EnsembleJudgeServiceTest | 3 (`metadataOnlyMatrix×2`, `judgePromptContains...MatrixBlock`) | `EnsembleEvidenceMatrix` 존재·`supportPromotionDisabled`/`unverified_evidence_matrix` 키 emit 확인됨 — matrix semantics(assert diff) 없이는 판별 불가 |

### UNRELATED — 확정 개별 사유 (7)
| suite | tests | basis |
|---|---|---|
| ChatFrontendSecurityTest | 5 잔여 | 구조적 template/css 재설계: `data-orch-badge` strip → `data-orch-field` status-pill 교체(transformerCore); `<details class="response-settings">` → `<div>`(newChatAction); short-desktop media query 제거·재구조화(shortDesktop/topUtilityBar/statusRail). madasin 접촉 파일 아님 — 계약 재기반화는 제품 판단 필요 |
| ApiExceptionResponseRedactionContractTest | 1 | `traceSuppressed("stream.initial"/"stream.tail")` — DebugEventsDiagnosticsController는 `session.sendEvents(...,"stream.initial")` 인자 라벨 구조(catch-stage 아님); madasin 접촉 없는 진단 채널 |
| EnsembleJudgeServiceTest | 1 (`judgeModelDefaultsToJudgeThenHighLane`) | yaml `judge-model` default → `${LLM_JUDGE_MODEL:smtek/Qwen3.8-27B:Q3_K_XL}`(GPU-lane spec drift); source @Value chain은 test 기대와 일치 — model-lock 정책상 yaml 회귀 패치 금지 |

### EMF_INFRA-suspect (3)
| suite | tests | basis |
|---|---|---|
| OutboxDiagnosticsControllerSecurityTest | 1 | DefaultMultiCauseException — context 기동 계열 실패 |
| AgentApplicationIntegrationBeanNamesTest | 2 | NoSuchBeanDefinitionException — bean wiring 계약(테스트 컨텍스트 or prod bean drift 둘 다 가능) |

### UNRELATED — madasin 접촉 밖 서브시스템 (그룹 추정 64 + 확정 7 = 71)
madasin touch set(`ChatApiController`, `ChatWorkflow`, `UnifiedRagOrchestrator`, `chat.js`, `chat-trace-ui.js`, `chat-ui.html`, `ChatTraceSnapshotPointerPersister`, `chat-trace-ui.test.cjs`)과 무관한 서브시스템. PROJECT_STATUS에 2026-09-24(이 작업 이전) 시점 이미 ~113 실패 기록 — 대부분 기존 계약 drift로 추정하되, 개별 검증 없이 확정 아님. 확정 7건(FrontendSecurity 5 + ApiExceptionResponseRedaction 1 + EnsembleJudgeService 1)은 위 표에 개별 근거.

- ensemble (1): EnsembleEvidenceMatrixTest ×1
- assist/display (5): ConversateCueLifecycleTest ×3, DisplayConversateHttpTest ×2
- boot/config contract (5): RuntimeConfigGuardTest, RuntimeConfigShadowGuardTest ×2, RuntimeVerificationRunbookTest, LocalLlmApplicationConfigContractTest(11435↔11434 dual-lane drift — source-checked), GuardPropertyBindingContractTest
- graph/rag/graphdb (8): BrainStateServiceTest ×2, BrainStateDuplicateChunkCharacterizationTest ×2, GraphRagThumbnailDiagnosticsFlowTest ×2, OfflineGraphRagReplayTest, CitationGateEvidenceComposerBoundaryContractTest
- graphdb boundary (2): GraphDbActivePackageBoundaryTest, GraphDbManualLearningControllerTest
- search/web provider (8): GrokPromotionProviderTest ×3(IllegalStateException @ `collect`:133 — ReflectionTestUtils signature drift → STALE_FIXTURE 성향), SearchProviderHttpContractTest ×3, NightmareBreakerProviderPermitBoundaryTest, HybridGrokPromotionDiscoveryTest
- llm/misc (9): ChatWaitRoutingFocusedTest ×2(DynamicChatModelFactory reflection — codex LLM seam 인접), BoundedProviderChainTest, CloudModelMetadataProbeTest, ExactRequestedModelTest, ProviderStatusProjectionTest, GlmAgentCoreTest, LmsApplicationSubagentHttpAcceptanceTest, Phase2ApplicationCapabilityTest, Phase2ApplicationRoleTest(admin/auth)
- infra/resource contract (5): ChatFailoverAdmissionHttpTest(NoSuchFileException — fixture 경로), NaverCredentialResourceContractTest, ScheduledJobGovernanceContractTest, ReflectionDebtAuditTest, VectorDiagnosticsControllerSecurityTest
- api misc (1): DesktopRouterStatusBridgeControllerTest

총계: EMF_INFRA 15 + codex-fixed 9 + devin-patched 19(REGRESSION 1 + STALE 18) + codex in-flight 10 + UNKNOWN 8 + EMF-suspect 3 + UNRELATED 71 ≈ 121 (parameterized 레코드 기준)

## Patch 상태
- Devin edits(sealed): `ChatApiControllerTraceMetaTest`, `ApiStreamDiagnosticRedactionContractTest`, `ChatWorkflowAgentVisibleDebugEvidenceTest`, `ChatWorkflowCancellationContractTest`, `RequestTraceTest`, `ChatWorkflowLlmTimeoutContractTest`, `ChatFrontendSecurityTest`, `main/resources/application.yml`(naver client-secret quoting), `main/java/.../ChatApiController.java`(empty-catch → `logSuppressed("stream.traceStash", e)`), `scripts/codex_work_checkpoint.py`+`test_codex_work_checkpoint.py`(scanner: quoted env-placeholder reference exemption + regression test — AGENTS sanctioned path)
- 재검증 완료: `verify-stale-fixtures` (5클래스: 4 green + FrontendSecurity 9잔여) → `verify-stale-fixtures-2` (RequestTrace 17/17, LlmTimeout 20/20, SafePatch 6/6 green; FrontendSecurity 5잔여) → `verify-frontend-4` (method-scoped 4건)
- FrontendSecurity 잔여 5건 = UNRELATED 구조 drift — 계약 재기반화/제품 판단 필요, 본 작업 범위 밖
- ChatApiController empty-catch 패치는 `test` 태스크 컴파일+대상 테스트 green으로 검증됨(verify-stale-fixtures run의 sourceIdentity 포함)
