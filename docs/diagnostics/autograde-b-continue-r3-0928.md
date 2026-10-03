# AutoGrade B CONTINUE R3 — explicit memory mode survives the next turn

Contract: DEMO1-AUTOGRADE-B-CONTINUE-R3-20260928
Task: autograde-b-r3-0928-6251af7f
Root: C:/AbandonWare/demo-1/demo-1/src
Evidence date: 2026-09-28 UTC

## Result and scope

R3 bounded acceptance: smoke GREEN, T-STRUCT repaired without changing product strict-attempt logic, and M5(a) explicit memory-mode session persistence repaired with RED-to-GREEN proof. Platform-wide health remains partial.

R2 D1/B02 behavior is retained. B04 was not selected without an established overblocking reproduction; the fallback M5 candidate was verified from current source and a new regression. The referenced Devin report was a hypothesis/evidence pointer, not mutation authority. The current R3 objective owns this work. Default HYBRID remains read-only.

## Changes

- main/java/com/example/lms/api/ChatSessionMetaMerger.java: add seven lines to save an explicitly supplied memoryMode in session metadata and restore it only when the next request omits the field. Explicit values retain MemoryMode's existing fallback semantics.
- src/test/java/com/example/lms/api/RagMemSteerDynamicToggleProbeTest.java: replace the previous missing-field characterization with FULL/HYBRID/EPHEMERAL round-trip regressions; verify FULL-to-HYBRID/EPHEMERAL overrides, the fresh-session HYBRID default and the independent memory-policy denial. The restored value passes through the real settings merger, enum and final-answer postprocessor.
- src/test/java/com/example/lms/service/ChatWorkflowPostOrchestrationPromptBoundaryTest.java: require the current exact strictSingleAttempt OR condition (preparedContextPacket or three-role candidates). All downstream no-retry/no-fallback assertions remain. ChatWorkflow product logic was not edited.

Before M5: explicit FULL was not recorded; an omitted next-turn field resolved to HYBRID and memorySaveAllowed=false/write_disabled. A pre-existing stored FULL also failed to update to an explicit HYBRID or EPHEMERAL in session metadata.
After M5: the explicit mode survives omission; FULL reaches the existing save gate, HYBRID/EPHEMERAL remain write-disabled, and evidence-policy denial still produces memory_policy_denied. This does not claim a real provider answer or real DB write.

## Fresh verification

Java 17; AWX_SPLIT_BUILD_OUTPUTS=1; AWX_BUILD_HOST_ID=desktop. All Java commands used gradlew.bat :test with only the named --tests selectors, --no-daemon --console=plain. Exact argv, XML, hashes and exit codes are under this task's evidence root.

| Run | Result |
|---|---|
| verify-smoke: ChatApiAgentPromptEvidenceTest | exit 0; 13/13 |
| verify-ui-smoke: node --test src/test/js/chat-search-outcome.test.cjs | exit 0; 5/5 (Node log, not JUnit) |
| verify-struct-red: ChatWorkflowPostOrchestrationPromptBoundaryTest | exit 1; 18 total, 1 expected stale-assignment failure |
| verify-struct-green: same class after test-only correction | exit 0; 18/18 |
| verify-m5-red: two parameterized memory-mode regression methods | exit 1; 5 expected assertion failures, 0 errors |
| verify-m5-green-final: seven focused suites | exit 0; 66/66 |
| initial broader M5 regression | exit 1; 109 total, 5 failures in ChatWorkflowFinalVerificationReleaseGateTest |
| verify-release-preimage: release-gate class after automatic source rollback | exit 1; 43 total, same 5 failures and messages |
| source diff hygiene | git diff --check on the three owned source/test paths, exit 0 |

The 66 passing focused cases: RagMemSteerDynamicToggleProbeTest 11; ChatSessionMetaMergerTest 7; ChatRequestSettingsMergerTest 17; SessionSettingsPrecedenceTest 5; RagMemSteerReleaseContractProbeTest 11; FinalizedMemoryPersistenceTest 4; FinalAnswerPostProcessorTest 11.

Passing executions overlap and are not summed as unique tests. The RED checkpoint verifies expected failure characterization, not a passing product test run.

## Baseline failures and limits

The broader release-gate suite's five failures were reproduced with the exact original ChatSessionMetaMerger preimage SHA e3d3b2145c8fba53f01198ed51e75cbea7b11b280f20b7aea931dac39b90c224. release-baseline-comparison.json confirms identical test names and failure messages between the patched and preimage executions:
- evidenceZeroWithoutDirectivePublishesDraftButSkipsDurableMemory;
- evidenceReleaseHoldWithFullMemoryNeverInvokesDurableWriters;
- explicitStopSuppressesEnabledOptionalWorkAndMemoryOnlyWhenEnforced (enforce and shadow);
- mismatchedWorkflowOwnerProducesNoPromptLocalDocuments (three-argument attachment mock expectation versus four-argument invocation).

They are pre-existing relative to this M5 change; their underlying causes are not resolved by this task. Do not generalize the 66-case pass to the release-gate suite or whole project.

project-intro UI: NOT_RUN in R3, retained as the R2 preimage-confirmed adjacent failure. The template and legacy UI harness were not changed. M9 searchMode omission remains outside this selected M5 seam. Admin browser, full suite, A*/performance F, real provider E2E, glasses and DB mutations were NOT_RUN.

## Runtime and H2

Final runtime: launcher var/rag-launcher/20260928-205534-8f8be943/result.json, status=ready, springReused=false, PID 10188 started 11:56:57Z after ChatSessionMetaMerger.class at 11:54:05Z. HTTP /chat and management health both 200. DevWatch socket-ready at 20:57:25 KST. Final Verify-RAG exit 0, freshness=current, sourcesNewer=false, target=partial because of H2 warnings. runtime-identity.json binds source/class hashes and process times.

The first R3 Verify-RAG returned exit 0/current but JVM 41164 started before the final merger class was built. This was rejected as freshness proof. The final forced restart and separate Verify-RAG are required evidence.

H2 remains diagnosis-only. Both the exact R2 launcher log (20260928-185342-1816af96) and this final R3 launcher log (20260928-205534-8f8be943) classified 132 DDL error-containing lines, 66 Hibernate warning entries, and 32 table + 26 index + 8 constraint already-exists causes. This is a count/classification, not proof that the overall schema is healthy or all errors are harmless. No drop, repair or DB write was performed.

## Ownership and handoff

conflicts_ATT_CTX_NW=none on the changed targets. The historical m21222ain journal was superseded; target checks found no overlapping live lease. Foreign journals, unrelated expired debug-script lease and foreign staged SelfAskPlannerOwnershipContractTest.java were retained.

One intermediate immediate-verify guard returned exit 6 before any edit; target bytes stayed unchanged. A fresh allowed target check and guarded invocation succeeded. The initial broad test failure triggered automatic source rollback; the final change was reapplied only after baseline classification. Every owned source-edit lease was released.

No commit, staging change, push or remote operation. HEAD 2d18b143beec7b5c68a3ed2e2931caa0dc26f6c6, branch codex/owned-runtime-browser-restart. B02 controller and original B02 test hashes still match R2. GLM remains SESSION_UNAVAILABLE from the prior unsupported-model failure; no review success or repeat call is claimed.

Evidence root: data/agent-handoff/codex-autonomy/autograde-b-r3-0928-6251af7f/.
Final changes: cycle-01-struct, cycle-02-m5-red, cycle-04-m5-source. cycle-03-m5-source was rolled back. Preserve all source preimages, failure evidence and reports.

Handoff: R3 T-STRUCT and M5(a) verified; keep default HYBRID and R2 D1/B02, track the five baseline release-gate failures and H2 classification separately, stop this bounded task.

