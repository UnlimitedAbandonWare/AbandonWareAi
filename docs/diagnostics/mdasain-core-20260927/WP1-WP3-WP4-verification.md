# mdasain CURRENT — WP0 / WP1 / WP3 / WP4 verification

Task: `mdasain-core-36400ed6` · root: `C:/AbandonWare/demo-1/demo-1/src` · evidence checked: 2026-09-27 11:22 UTC.

**The authorized source implementation is delivered; the whole repository is not green.** Live web.search remains OFF. No commit, push, auth change, WP2 edit, old-archive replacement, new provider, or new endpoint was made.

## Work-package disposition

| WP | Status | Delivered evidence and limit |
| --- | --- | --- |
| WP0 | IMPLEMENTED | WP0-intake.md records actual Gradle roots, Java 17, sourceSets, entry point, bean graph and attachment drift. Baseline run exited 0. |
| WP1 | IMPLEMENTED | Default-OFF effective catalog/registry/invocation; missing gateway stays unavailable. Existing query/searchAndRank retained. YAML-eligible providers share a request deadline and at most two provider attempts; Brave quota ownership stays in the existing adapter. |
| WP3 | IMPLEMENTED | YAML eligibility/tier filtering before existing bandit selection; lowest eligible tier also applies to fallback. Existing credential, health, capability and capacity checks remain. Agent-only paid/replay/retry rules follow the spend YAML; production_hard_caps remains false. |
| WP4 | IMPLEMENTED | Approved synchronous, request-local bounded Content reaches the canonical com.example.lms.prompt.PromptContext path. Maximum three canonical HTTP(S) sources, 600 characters per snippet. Diagnostic redaction remains separate. Empty/failure/cancelled tool results cannot start the legacy retrieval or Needle Probe path. |
| WP2 / live activation | BLOCKED for this task's online activation only | WP2 belongs to Devin. A peer status row exists, but this task did not independently re-establish its whole current AC set. No live flag ON or online documentation question was attempted. |
| WP5–WP8 | NOT_RUN | Optional and outside this delivered slice. |

The default-OFF and ON-with-mocked-gateway cases are Spring context tests. They do not claim production provider availability or official-document coverage.

## Fresh verification

| Run | Actual result | Interpretation |
| --- | --- | --- |
| verify-final-green | **18 suites; 128 tests; 0 failures/errors/skips; exit 0** | Final affected Java source and adjacent admission, tier, security, prompt/citation and no-extra-search boundaries. |
| Python scanner verification | **40 tests; 0 failures; exit 0** | Exact fixture/runtime-expression exceptions retain literal/comment/secret rejection. |
| verify-full | **1,441 suites; 11,758 tests; 76 failures; 0 errors; 12 skipped; exit 1** | Broad root :test completed in 15m5s. Started before the final boundary/fixture corrections; not a final whole-tree green claim. Full per-test dispositions are retained in its suite-summary.json. |
| verify-last-red | 54 tests; 5 failures | Reproduced the final local-fallback and missing-gateway admission gaps. These and the misplaced source-test guard were fixed and included in the 128 green tests. The adjacent timeline suite had 14 tests / 2 remaining failures. |
| runtime-start | exit 0; run 20260927-201725-1ae48ab5; ready; springReused=false | Fresh dev role on 18180/18181/18182; shared Ollama reused. Wear role was not restarted. |
| runtime-verify | exit 0; status=verified; **target=partial**, 9 checks / 1 warning / 0 notRun | Compile/processResources, identity, ports, HTTP, freshness and DevWatch observed. PID 35624; sourcesNewer=false. H2 DDL duplicate-index/constraint diagnostics remain (128 matching exception/error lines, not 128 unique defects). |
| GET /internal/agent/tools | HTTP 404 | The existing API has an independent agent.tools.api.enabled gate. This response is not evidence of the effective web-search flag or of successful tool invocation. No route/auth gate was changed to expose it. |
| Live online question / paid generation | NOT_RUN | No explicit online search or paid-generation verification request was sent. Startup/health requests are not semantic provider proof. |

Commands: `gradlew.bat --no-daemon --console=plain :test --tests <recorded suite selectors>`, full `:test`, the two scanner unittest modules, matching Close-RAG plus headless Start-RAG script, then Verify-RAG.bat. Test builds used `AWX_SPLIT_BUILD_OUTPUTS=1` and `AWX_BUILD_HOST_ID=desktop-mdasain-core`; exact arguments, times and exits are in each run.json/command.log.

Broad failures are classified by surface, not declared pre-existing. Families include security/HTTP, provider projection, Display lifecycle, GraphRAG, prompt/timeout and source-literal contracts. Two adjacent LlmRouterRequestTimelineTest assertions still fail: Responses fallback expects responses_http_503 but receives failover_exhausted, and malformed Responses output remains responses_contract_error. Their origin is unresolved; no response-protocol change was added to hide them. One timeout source-literal assertion also fails against this task's exact preimage; timeout-preimage-proof.json records that predicate, not a fictitious baseline JUnit run.

## Per-file change and evidence

Paths below are repository-relative. The final-applied.patch and owned-files.json bind exact preimages, current hashes and per-file line counts.

| File | Change / primary proof |
| --- | --- |
| main/java/com/abandonware/ai/agent/contract/ToolManifestCatalog.java | Effective default-OFF and registry availability; AgentToolOpsConfigContextTest. |
| main/java/com/example/lms/config/AgentToolOpsConfig.java | Conditional startup registration using existing registry; context OFF/ON/no-gateway tests. |
| main/java/com/example/lms/config/AcmeWebSearchAdapterConfig.java | Existing gateway and WeightedRrfRanking bean assembly; AcmeWebSearchContextTest. |
| main/resources/tool_manifest__kchat_gpt_pro.json | Conditional capability declaration; effective catalog owns OFF. Existing scopes/owner/risk preserved. |
| main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java | **Comment only**; searchAndRank implementation unchanged. |
| main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java | Eligible ordered providers, shared count/deadline, distinct empty/failure/skip outcomes; gateway policy and trace tests. |
| main/java/com/example/lms/routing/ApiRoutingPolicySnapshot.java | Separately parsed immutable YAML read model; no routing engine or network; policy tests. |
| main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java | Cheapest eligible tier before exploration/UCB; tier and trace tests. |
| main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java | Selection/fallback policy plus agent-only spend boundary; tier, device-failover, contention and security tests. |
| main/java/com/example/lms/routing/ApiSpendAttribution.java | Profile/env activation and purpose-scoped verification replay; spend wiring/policy tests. |
| main/java/com/example/lms/routing/AgentApiSpendGuard.java | YAML-aligned agent paid/stale/replay checks; ordinary user replay preserved. |
| main/java/com/example/lms/llm/DynamicChatModelFactory.java | Pass actual Spring environment policy into existing spend guard. |
| main/java/com/abandonware/ai/agent/tool/AgentToolInvoker.java | Existing approval boundary plus bounded same-request sink; effective availability and late-result rejection; prompt/security tests. |
| main/java/com/example/lms/api/ChatApiController.java | Capture existing authority; use typed supplier only for effective admitted tool requests; preserve default/public supplier; controller evidence test. |
| main/java/com/example/lms/service/ChatWorkflow.java | Typed provenance into existing canonical prompt/citation path; no unbudgeted Needle Probe after tool-owned result; workflow contract/citation tests. |
| scripts/codex_work_checkpoint.py | Narrow fix for actual scanner false positives; exact synthetic fixtures and typed runtime AuthorizationDecision expression only. |
| scripts/test_checkpoint_java_call_args.py | Regression coverage for those scanner exceptions and negative cases; 40 Python tests. |
| src/test/java/com/example/lms/config/AgentToolOpsConfigContextTest.java | OFF, ON, missing gateway, effective invocation and zero-call cases. |
| src/test/java/com/example/lms/config/AcmeWebSearchContextTest.java | Real adapter/ranking/gateway/registry Spring assembly with synthetic provider dependencies. |
| src/test/java/com/abandonware/ai/agent/integrations/AcmeAICoreGatewayTraceTest.java | Existing diagnostics cases adapted to eligible synthetic IDs and separate requests; no redaction relaxation. |
| src/test/java/com/abandonware/ai/agent/integrations/AcmeAICoreGatewayPolicyTest.java | Provider order, shared cap/deadline admission and zero-call guards. |
| src/test/java/com/example/lms/routing/ApiRoutingPolicySnapshotTest.java | YAML identity/order, role allowlists, profile activation and paid override. |
| src/test/java/ai/abandonware/nova/orch/aop/LlmRouterTierPolicyTest.java | Cheapest eligible primary/fallback and ordinary-user/agent separation. |
| src/test/java/ai/abandonware/nova/orch/aop/LlmRouterRequestTimelineTest.java | Fixtures declare YAML model/provider and eligible probe; two adjacent protocol expectations remain unresolved. |
| src/test/java/com/abandonware/ai/agent/tool/AgentPromptEvidenceTest.java | Bounded canonical Content, no raw diagnostic snippet, authority/allowWeb/cancel/late rejection, status distinctions. |
| src/test/java/com/example/lms/api/ChatApiAgentPromptEvidenceTest.java | Controller admitted supplier retains metadata and canonical URL despite a decoy URL in text. |
| src/test/java/com/example/lms/service/ChatWorkflowAgentWebBudgetContractTest.java | Source call-site guard prevents second-pass Needle Probe after tool-owned results. |
| src/test/java/com/example/lms/service/OperationalQualitySourceContractTest.java | Locate the actual outer FORCE_LIGHT prefetch guard after typed-supplier nesting. |
| docs/diagnostics/mdasain-core-20260927/WP0-intake.md | Intake evidence and final verification pointer. |
| docs/PROJECT_STATUS.md | Task result, source/built/running distinction, verification counts and activation hold. |

## Preserved boundaries and handoff

- Policy resource hashes remain api-routing.yaml `768309483bdc7466bfb2979896e086d701bfe9f806ca7e8077afcd52db676439` and agent-api-spend-guard.yaml `f97299f8fe1253c070d7b0b9e895bb89a52b258150e1bbd4bd51a4d05014c827`.
- No WP2 owner files, SafeRedactor, auth filters, UI, database schema, model inventory or GPU settings were edited. No secrets were read or emitted. All acquired source leases were released by their finally path.
- The GLM review lane returned SESSION_UNAVAILABLE (unsupported model on this account); no success or independent GLM approval is claimed. A built-in read-only explorer checked the late-search boundary and found the fixed Needle Probe bypass. AWX build_error_mine narrowed build/test failure classes; real Gradle/JUnit output remained authoritative.
- Preserve source preimages, final patch, reports, run logs and recovery bytes. No surplus artifact deletion is needed; no downloaded directive is deleted or marked as fully online-accepted.
- Next activation action: revalidate Devin WP2's exact current evidence, then perform at most one approved documentation question and record child-provider calls. Keep the live flag OFF until that boundary is satisfied. Broad-suite failures and H2 warnings remain separately tracked; no full-system-health claim is made.

Evidence root: `data/agent-handoff/codex-autonomy/mdasain-core-36400ed6/`.
