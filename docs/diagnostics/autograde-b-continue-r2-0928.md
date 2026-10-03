# AutoGrade B CONTINUE R2 — D1 projection and B03 message proof

Contract: DEMO1-AUTOGRADE-B-CONTINUE-R2-20260928
Task: autograde-b-r2-0928-c61e71b4
Root: C:/AbandonWare/demo-1/demo-1/src
Evidence date: 2026-09-28 UTC

## Result and scope

D1 is patched and verified. B03 is GREEN / NO_CHANGE_VERIFIED for allowed web/vector evidence in actual final model messages. These satisfy the bounded R2 acceptance; platform-wide health is partial.

prior_accepted: B00=Done; B02=agentPromptSearch boundary; B01=NO_CHANGE. This session did not reimplement them. The named 4437a3be goal and CONTINUE R2 were intake; older TARGETS and broad plugin instructions were treated as references where they conflicted. No old attachment was treated as current runtime proof.

## D1: actual first missing boundary

Existing ChatApiController.agentPromptSearch correctly writes request-local OK / FAIL_SOFT / SKIPPED, reasonCode and returnedCount. The shared ChatStreamSignalBuilder/PipelineSnapshot projection omitted those observations from ordinary API and SSE payloads.

The patch adds pipelineSnapshot.agentWebSearch with only status, sanitized reasonCode and returnedCount; preserves it while attaching a trace-turn ID; retains a snapshot containing only this observation; and renders it in the existing chat trace diagnostic. Absent observations remain absent. It does not make a search failure a global answer failure, invoke providers, change admission or move failure text into prompt evidence.

Changed product paths:
- main/java/com/example/lms/api/ChatStreamSignalBuilder.java
- main/java/com/example/lms/dto/ChatStreamEvent.java
- main/resources/static/js/chat.js (six added lines in this task's preimage diff)

Tests:
- src/test/java/com/example/lms/api/ChatApiControllerSyncLifecycleTest.java: five new cases through the ordinary sync controller, actual tool supplier/invoker, public serialization and shared SSE projection.
- src/test/js/chat-search-outcome.test.cjs: five renderer cases.

RED accounting: the first two runs also exposed missing presentation/admission setup in the new test fixture. The final corrected RED explicitly selected FORCE_LIGHT for enabled requests and used the real presentation boundary; all five failures then reached the missing public outcome assertions. All original B02 assertions were preserved.

## B03: actual messages, not trace-only

ChatWorkflowPromptMessageRoleTest captures the List<ChatMessage> received by final ChatModel.chat. Its web-only, vector-only, combined, post-selection and compression cases pass. StandardPromptBuilderEvidenceMetadataTest also passes. No B03 product change was justified by this evidence; no ensemble relaxation or new SystemMessage promotion was applied.

Live remap:
- ChatApiController.java:3132 agentPromptSearch; callers at 2304 and 4392.
- ChatStreamSignalBuilder.java:95 buildPipelineSnapshot; agentWebSearchSnapshot and withTraceTurnId own the repaired public projection.
- ChatWorkflow.java:2888-3010 selects allowed documents and fills PromptContext; 3312-3529 builds messages; 3663-3679 and 7174-7180 reach the final model call.
- ChatWorkflowPromptMessageRoleTest.java:170 captures actual final message membership.
Current source hashes are bound by the verification run.json files and per-change checkpoints.

## Fresh commands and results

All Java runs used Java 17, AWX_SPLIT_BUILD_OUTPUTS=1, AWX_BUILD_HOST_ID=desktop and --no-daemon --console=plain. The exact argv, times, source hashes and scoped XML accounting are in the task directory.

| Run | Command / selected suites | Exit / result |
|---|---|---|
| B02 minimum smoke | gradlew.bat :test --tests com.example.lms.api.ChatApiAgentPromptEvidenceTest | 0; 13/13 |
| Corrected D1 RED | gradlew.bat :test --tests com.example.lms.api.ChatApiControllerSyncLifecycleTest.agentSearchOutcomeSurvivesOrdinaryChatResponseAndStreamProjection --tests com.example.lms.api.ChatApiControllerSyncLifecycleTest.absentSearchObservationDoesNotReusePreviousOutcome | 1; 5 expected assertion failures, 0 errors |
| D1 GREEN | gradlew.bat :test --tests com.example.lms.api.ChatApiControllerSyncLifecycleTest --tests com.example.lms.api.ChatStreamSignalBuilderTest --tests com.example.lms.api.ChatApiAgentPromptEvidenceTest | 0; 80/80 |
| D1 UI | node --test src/test/js/chat-search-outcome.test.cjs | 0; 5/5 (Node log; runner's JUnit count is 0) |
| B03 core | gradlew.bat :test --tests com.example.lms.service.ChatWorkflowPromptMessageRoleTest --tests com.example.lms.prompt.StandardPromptBuilderEvidenceMetadataTest | 0; 17+6=23/23 |
| Broader B03 boundary check | same two B03 suites plus ChatWorkflowPostOrchestrationPromptBoundaryTest | 1; 41 total, 40 pass, 1 structural assertion failure |
| Legacy UI regression | node scripts/chat_ui_stream_contract_tests.js | 1; missing project-intro markup assertion at line 1492; same failure with this task's preimage substitutions |
| Runtime start | powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -TimeoutSeconds 600 | 0; new PID 7800, springReused=false |
| Runtime verify | cmd /c Verify-RAG.bat | 0; freshness=current, sourcesNewer=false; target=partial |
| Diff hygiene | git diff --check -- [four changed tracked source/test paths] | 0 |

Passing executions overlap and are not summed as unique tests.

## Limits and diagnostic findings

The broader B03 failure is finalAnswerPathUsesPrimaryOnceAfterOptionalReferenceSampling, which requires the exact source string strictSingleAttempt = hasThreeRoleRefinementCandidates(ctx). Current code adds ctx.preparedContextPacket()!=null OR that same condition. The actual final-message tests pass. The source-string assertion was neither weakened nor counted as passing; this run alone is not a preimage baseline for that Java test.

The legacy UI script expects an aside.project-intro in chat-ui.html. Current markup does not contain it. The same assertion failed when the harness read this task's preserved chat.js and ChatStreamSignalBuilder preimages; no template change was made.

H2 is diagnose-only: final Verify-RAG reports exception/error-level counts 132 and target=partial. The launcher log contains table/index/constraint-already-exists DDL failures (codes 42101-224, 42111-224, 90045-224); repeated log entries are not 132 independent database defects. No DB mutation or repair was performed.

Initial Verify-RAG returned exit 0/current for PID 27020 although its start preceded the changed Java classes. That was not accepted as freshness proof. The owned ForceRestart produced PID 7800 at 09:55:10Z, after the changed class files; served /js/chat.js SHA-256 equals the current source. DevWatch socket-ready and final Verify-RAG independently completed. Runtime identity is saved in runtime-identity.json.

admin_browser=SKIPPED; full_suite=NOT_RUN; real_provider_E2E=NOT_RUN; glasses=NOT_RUN; A*/F*/B04-B09=NOT_RUN.
conflicts_ATT_CTX_NW=none on the changed paths. Unrelated foreign leases were preserved; every owned source-edit lease was released.
GLM review=SESSION_UNAVAILABLE (unsupported model, no task-delivery proof); no review success claimed.
HEAD=2d18b143beec7b5c68a3ed2e2931caa0dc26f6c6; no staging operation, commit, push or remote operation. Foreign staged SelfAskPlannerOwnershipContractTest remained present. Original B02 product/test hashes match the accepted report.

## Evidence and handoff

Evidence root: data/agent-handoff/codex-autonomy/autograde-b-r2-0928-c61e71b4/
Required: verify-b02-smoke, verify-d1-red-admitted, verify-d1-green, verify-d1-ui, verify-b03-core, runtime-start, runtime-verify-final, runtime-identity.json.
Adverse evidence: verify-b03, verify-ui-preimage, verify-ui-legacy. Checkpoints contain preimages, postimages and exact owned diffs.
An attempted checkpoint of the legacy UI harness was refused by its existing secret-pattern scan; that file was not edited or copied into a checkpoint. The narrow new renderer test exercises this change without altering that harness or weakening its scanner.

Handoff: D1 public outcome projection verified; B03 actual-message evidence verified without source changes; DB DDL and two adjacent test observations remain separately recorded. Stop this bounded R2 task.
