# WP0 intake — mdasain CURRENT

Task: `mdasain-core-36400ed6`; observed 2026-09-27 UTC.

Final follow-up: WP1/WP3/WP4 source delivered; 18 focused Java suites / 128 tests and 40 Python tests pass. Fresh dev runtime Verify-RAG exits 0 with target=partial (H2 DDL warning). Broad root test has 76 failures / 11,758 tests; live activation remains OFF. See [WP1-WP3-WP4-verification.md](WP1-WP3-WP4-verification.md) for final per-file evidence and limits. Intake-time NOT_RUN/pending statements below describe the initial observation, not the final result.

**Upgrade CURRENT, never replace CURRENT with OLD.** This is live-source intake, not proof of a running feature.

## Authority and scope

- User explicitly authorizes WP0, WP1, WP3 and WP4 product changes and focused verification. No commit/push, paid fanout, auth changes, or live web-search enablement.
- Canonical repository and build root: `C:/AbandonWare/demo-1/demo-1/src`, confirmed by preflight and `git rev-parse --show-toplevel`.
- HEAD at intake: `2d18b143beec7b5c68a3ed2e2931caa0dc26f6c6`; branch `codex/owned-runtime-browser-restart`. Remote SHA comparison: NOT_RUN; no remote snapshot used as source authority.
- WP2 owners' files, SafeRedactor and Debug Events UI are excluded. Existing status row `mdasain-wp0-wp2-safety-84bf9ff0` reports 73 focused tests; that is a peer claim, not this task's live acceptance proof.
- No video was supplied for this objective. The unrelated login/logout appendix is explicitly excluded by the user.

## Build and execution

- Java observed: 17.0.13. Active settings: `settings.gradle` (root `src111_merge15`, `:app` included). `settings.gradle.kts` explicitly labels itself a sentinel.
- Root build: `build.gradle.kts`; Spring Boot plugin 3.3.4; LangChain4j dependencies pinned to 1.0.1 with a version guard.
- Active root sources: `main/java`, `main/resources`; tests: `src/test/java`, `src/test/resources`. Main class: `com.example.lms.LmsApplication`.
- LmsApplication scans `com.example.lms` and `com.nova.protocol`; AgentToolOpsConfig explicitly supplies ops tools/registry/invoker. AgentApplication is a separate entry point, not the default bootRun main.
- Start-RAG contract selects profiles `local,meta-display`. Actual running PID/profile/bean graph: NOT_RUN at intake; configuration declarations do not prove running beans.
- Tests use `AWX_SPLIT_BUILD_OUTPUTS=1`, `AWX_BUILD_HOST_ID=desktop-mdasain-core` to avoid sibling output collisions.
- Baseline command started: `gradlew.bat --no-daemon --console=plain :test --tests com.example.lms.config.AgentToolOpsConfigContextTest --tests com.abandonware.ai.agent.tool.AgentWebSearchToolConditionalWiringTest --tests com.abandonware.ai.agent.integrations.AcmeAICoreGatewayTraceTest`. Result pending in task `verify-wp0-baseline`; not green by assertion.

## Source-backed graph and gaps

1. Manifest `web.search` is statically disabled. ToolManifestCatalog reads the static entry without an effective feature flag. AgentToolOpsConfig discovers/registers AgentTool beans but supplies no WebSearchTool bean.
2. WebSearchTool already calls WebSearchGateway.searchAndRank(query, topK, lang), preserves topK 1–20/default 5 and lang ko. Its empty-shim comment is stale; implementation replacement is unnecessary.
3. AcmeWebSearchAdapterConfig is gated by `adapter.acme-websearch.enabled`; it scans the existing Brave/Naver adapters. AcmeAICoreGateway requires RankingPort, sits outside the main scan, and currently loops all injected providers. No source-backed default-app gateway/ranking bean was found. Context tests will determine the narrow explicit assembly.
4. AgentToolInvoker resolves manifest/registry, checks scopes/owner/policy, executes once, then redacts data before inline/artifact output. That diagnostic response cannot transport semantic snippets. ToolContext.extras is immutable. WP4 must use an internal request-local semantic sink and the existing PromptContext builder boundary.
5. WP3 discovery: primary bandit candidates use gateway eligibility, but no YAML tier gate; auto fallback reconstructs candidates separately. Explicit model selection has a separate branch. Existing health/capability/capacity guards must survive.

## Policy identity and attachment drift

| Resource | Version | SHA-256 |
| --- | --- | --- |
| main/resources/configs/api-routing.yaml | 2026-09-18 | 768309483bdc7466bfb2979896e086d701bfe9f806ca7e8077afcd52db676439 |
| main/resources/configs/agent-api-spend-guard.yaml | 2026-09-17 | f97299f8fe1253c070d7b0b9e895bb89a52b258150e1bbd4bd51a4d05014c827 |

These match the attachment's resource hashes. The attachment lacks build files, while live has a complete Gradle build; its archive cannot substitute for this root. WP2 has since changed live source according to the status journal. Attached verification explicitly says `product_patch_applied=false` and build/JUnit/Spring/live API/SSE `NOT_RUN`; none of its future AC checkboxes are pass evidence.

## Planned smallest slices

- WP1: ToolManifestCatalog, AgentToolOpsConfig, AcmeWebSearchAdapterConfig, manifest, existing gateway and invoker admission seam; WebSearchTool comment/status only; focused context/gateway tests.
- Shared WP1/WP3 policy read model only if no equivalent exists: separate immutable parsing of the two resources, no routing engine, no network, no dependencies.
- WP3: current LlmRouterAspect/bandit candidate and fallback gates, AgentApiSpendGuard/ApiSpendAttribution; synthetic routing tests.
- WP4: invoker internal evidence sink and actual pre-build ChatWorkflow/ChatOrchestrator call path; existing canonical `com.example.lms.prompt.PromptContext`, source normalization and citation rendering reused.
- Each source patch gets exact target hashes, a target-scoped lease and preimage checkpoint. Default live search remains OFF throughout this task unless the required independent evidence is freshly proven.
