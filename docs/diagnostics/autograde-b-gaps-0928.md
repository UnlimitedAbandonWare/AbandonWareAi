# AutoGrade B Gaps: Verified B02 Repair

Contract: DEMO1-AUTOGRADE-B-GAPS-20260928-R1
Parent: DEMO1-AUTOGRADE-B-TARGETS-20260928-R1
Task: autograde-b02-0928-74c07d0e
Root: C:/AbandonWare/demo-1/demo-1/src
Evidence date: 2026-09-28 UTC

## Result

B00 is NO_CHANGE_VERIFIED. B02 consumer repair is verified. B01 is NO_CHANGE_VERIFIED with 22 focused tests. This closes the bounded GAP session, not platform-wide health. G-P1 through G-P9 were addressed by the explicit scope overrides, live remap, RED/GREEN and actual consumer verification.

The WebSearchTool exception is already classified FAIL_SOFT by AgentToolInvoker. The first remaining broken boundary was ChatApiController.agentPromptSearch: it ignored the invocation result, collapsed all ToolInvocationException statuses to SKIPPED, and left stale consumer state on early returns. The patch consumes executionStatus, publishes request-local status/reason/count, retains evidence only for OK, distinguishes 408/429/5xx failures from skips, and resets prior consumer diagnostics. Failure details never enter prompt evidence. No new search service, retry, provider call, or wait interval was added.

Repro: admitted WebEvidenceSupplier -> real AgentToolInvoker -> throwing/failed gateway -> empty evidence with explicit FAIL_SOFT at the chat consumer; ordinary empty search stays OK and disallowed/no-provider search stays SKIPPED.

## Live Remap

| Point | Live symbol and line | SHA-256 before | Result |
|---|---|---|---|
| P01 | main/java/com/example/lms/config/WebMvcConfig.java:59 addInterceptors | 99730157e6d742dfc8ed738c0140187dab361c695fe83948177b83cb00f981c8 | Matches attachment; conditional RuleBreak registration present |
| P02 | main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports:1 | 5c647cb81692df339ef336f4acfc5dcc4e169d879278616ebaf9973fad22d241 | Matches attachment; six imports including FailurePattern and Zero100 |
| P06 | main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java:44 execute | aea768e073a0b8fd2eec4ebcecdee8daced3872da090dc0200eb709cebcfbca2 | Matches attachment; unchanged, existing invoker already classifies tool trace |
| B02 consumer | main/java/com/example/lms/api/ChatApiController.java:3132 agentPromptSearch | 0b0b8112a103e8245a3feccb9486c1c8eb754308ae65ce3a4b7989f7f130c598 | Patched after actual caller trace; after 138170a715cea26f3cec34a75d95f13c1de6644bdf5e6c36df6a83d61a3dbfae |
| B02 test | src/test/java/com/example/lms/api/ChatApiAgentPromptEvidenceTest.java:46 | aef28565b7e0c204bfd597666a256afb38662a383ea945d8a67875b17da8241b | Added 12 cases; after b47f27d4e911baee3cf60734d81a5770651afb7d596632c4d74709f0adffc5b8 |
| P03 | main/java/com/example/lms/api/ChatRequestSettingsMerger.java:18 merge; intent/defaults at 84 | db0f684f7ae9abf3329b1e135ac0c02c31eef7f08a2b45e19a4c9e057bd90877 | Differs from attachment; explicit false still wins; unchanged |
| P04 | main/java/com/example/lms/service/SettingsService.java:122 getAllSettings | cb2b3427361127c472983b1c5b5f8d38dbba2e32d93a443954853dcc5e52f74e | Matches attachment; unchanged |
| P05 | main/java/com/example/lms/prompt/PromptContext.java:91 constructor | 548ed4cf1c69a0541221f9330ce20bfb2acce769719fc18ea5d3b5b49efca797 | Differs from attachment; unchanged |

Grok assist instructions were read from the named Downloads pointer; no exact completed remap artifact was provided by it. The remap above was measured directly in the canonical checkout.

## Fresh Verification

All Gradle runs used :test --tests <named suites> --no-daemon --console=plain, Java 17, AWX_SPLIT_BUILD_OUTPUTS=1 and AWX_BUILD_HOST_ID=desktop. Exact arguments and source hashes are in each run.json.

| Evidence | Command/suites | Exit and counts |
|---|---|---|
| B00 | WebMvcRuleBreakRegistrationTest + NovaAutoConfigurationSourceContractTest | exit 0; 18 passed |
| RED | ChatApiAgentPromptEvidenceTest before product patch | exit 1; 13 total, 12 expected assertion failures, 1 original success, 0 errors |
| B02 GREEN | ChatApiAgentPromptEvidenceTest, AgentPromptEvidenceTest, AgentWebSearchToolConditionalWiringTest, AgentToolInvokerSecurityTest | exit 0; 13 + 4 + 19 + 23 = 59 passed |
| B01 regression | ChatRequestSettingsMergerTest + SessionSettingsPrecedenceTest | same GREEN exit 0; 17 + 5 = 22 passed |
| Final focused aggregate | Six GREEN suites | 81 passed, 0 failed/errors/skipped |
| Runtime | start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch; Verify-RAG.bat | both exit 0; new PID 36112, springReused=false, freshness=current, sourcesNewer=false, HTTP checks pass, DevWatch armed |
| Final diff | git diff --check on the two source/test files | exit 0 |

B00 evidence: data/agent-handoff/codex-autonomy/autograde-b02-baseline-0928/run.json.
RED/GREEN/runtime evidence: data/agent-handoff/codex-autonomy/autograde-b02-0928-74c07d0e/{verify-red,verify-green,runtime-start,runtime-verify}/.
Preimages and exact owned diffs: cycle-01-tests and cycle-02-source under the same task. The test-only checkpoint verified expected RED characterization, not a passing test suite.

Runtime limitation: Verify-RAG reports target=partial, fullVerification=false, H2 DDL exception/error counts of 132, and coverage gaps config-effective-binding and unit-tests-not-wired. Focused tests were separately run above. A similar H2 index error was observed in the pre-edit launcher trail; no database repair was attempted and no claim is made that every warning is pre-existing.

## Scope And Handoff

admin_browser=SKIPPED_BY_OVERRIDE; full_suite=NOT_RUN (forbidden). Real provider search/generation, glasses hardware and browser E2E were not required or run. B03/B04/B05/B06/B07/B08/B09 and AutoGrade A* remain outside this verified slice. NW/ATT/CTX, zero-evidence release and PROTO_OPEN were not changed; conflicts_ATT_CTX_NW=none. Product changes are limited to the consumer method; the other edited Java file is its focused test.

HEAD=2d18b143beec7b5c68a3ed2e2931caa0dc26f6c6; no commit, staging change, push or remote operation. Foreign staged SelfAskPlannerOwnershipContractTest remained present. Source-edit leases were released after each guarded cycle. The dev server remains available at http://127.0.0.1:18180/chat.
