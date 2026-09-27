# Provider Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development for every production behavior change and superpowers:verification-before-completion before any completion claim. Do not commit because this task has no commit authority.

**Goal:** Preserve the approved provider/Gemini/config working-tree postimages while centralizing credential resolution, routing every Gemini wire call through one bounded gateway, adding a single bounded zero-result search recovery path, and projecting redacted provider health into the existing `/chat-ui` diagnostics surface. Supabase remains unintegrated and disabled.

**Architecture:** `ProviderCredentialResolver` becomes the sole effective-credential decision point while compatibility methods remain on `KeyResolver`. `GeminiClient` remains the public compatibility facade, but `GeminiGateway` owns native REST and OpenAI-compatible Gemini model construction, per-purpose enablement, model selection, timeout, retry, quota, breaker, preflight, and redacted attempt telemetry. `HybridWebSearchProvider.search` owns a request-local `BRAVE -> NAVER -> local QueryTransformer rewrite -> one Gemini expansion -> one re-search` state machine; the existing fail-soft aspect recognizes the completed bounded route and performs no duplicate network rescue. Existing pipeline health and Chat UI heartbeat project exactly the approved provider fields rather than exposing raw trace maps.

**Tech Stack:** Java 17, Spring Boot, WebClient/Reactor, Resilience4j 2.2.0, LangChain4j 1.0.1, JUnit 5/Mockito, Gradle Wrapper, existing TraceStore/DebugEventStore, existing `/api/chat/ui-heartbeat` and `/chat-ui` Search diagnostics card.

## Global Constraints

- Canonical root is `C:\AbandonWare\demo-1\demo-1\src`; active backend sourceSets are root `main/java` and `main/resources`.
- Preserve all hashes in `data/agent-handoff/codex/ownership/provider-integration-20260818.json`; before each overlapping edit, re-hash the exact path and HOLD on mismatch.
- Run exactly the repository three-way preflight before the first Java/resource/test mutation, then acquire the existing `desktop` source-edit lease. Do not create another lock protocol.
- Keep all current property and environment aliases. Equal effective values are allowed with count-only/redacted diagnostics; distinct values disable only that provider and never disclose either value.
- The application never reads `apikey.txt`; no persistent environment values are created. A startup-script bridge is out of scope unless a live file format is separately provided.
- Keep all `dev.langchain4j` dependencies exactly `1.0.1`; add no dependency.
- Keep the global Gemini router off during first verification. Search expansion is the only enabled Gemini purpose by default; translation, understanding/generation, keyword/training, curation, and router remain purpose-disabled until explicitly enabled.
- Keep Supabase unintegrated: no dependency, client, schema, RLS, Data API key, MCP mutation, or database call.
- Provider status fields are exactly `provider`, `route`, `model`, `enabled`, `credentialPresent`, `attemptCount`, `statusCode`, `latencyMs`, `cacheHit`, `quotaDecision`, `fallbackReason`, and `errorClass`.
- Never store or render keys, Authorization headers, raw prompts, raw provider responses, raw queries, cookies, or environment dumps.
- Bounded search accounting is per `HybridWebSearchProvider.search` invocation. A whole-chat cap would require a separate propagated request budget and is not claimed here.
- Do not touch inactive mirror sourceSets or unrelated dirty files. Do not stage, commit, push, deploy, or stop the unrelated listener on port 8080.

## File Structure

- Create `main/java/com/example/lms/guard/ProviderCredentialResolver.java`: provider enum, alias inventory, equal/conflict semantics, redacted diagnostics.
- Modify `main/java/com/example/lms/guard/KeyResolver.java`: compatibility adapters delegate to the shared resolver.
- Modify the active Brave/NAVER/Tavily/SerpAPI consumers only where needed to consume the resolver without removing aliases.
- Create `main/java/com/example/lms/learning/gemini/GeminiGateway.java`: one native/OpenAI-compatible Gemini wire owner and provider status recorder.
- Modify `main/java/com/example/lms/learning/gemini/GeminiClient.java`: compatibility facade delegating every current operation to the gateway.
- Modify `main/java/com/example/lms/client/GeminiClientDecorator.java`: remove the competing `@Primary` wire/decorator path while retaining source compatibility if referenced.
- Modify `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`: Gemini route model construction delegates to the gateway; non-Gemini routes remain unchanged.
- Modify `main/java/com/example/lms/service/rag/pre/LongInputDistillationService.java`, `main/java/com/example/lms/llm/ModelMap.java`, `main/resources/application.yml`, and `main/resources/application-llm.yaml`: remove retired 1.5 defaults, externalize supported 2.5 models, and keep router disabled.
- Modify `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java` and `main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java`: bounded zero-result fallback and duplicate-rescue suppression.
- Modify `main/java/com/example/lms/agent/context/AgentPipelineHealthController.java`, `main/java/com/example/lms/web/ChatUiHeartbeatPayload.java`, `main/java/com/example/lms/web/ChatUiCoreHeartbeatProbe.java`, and `main/resources/static/js/chat.js`: exact allowlisted provider status projection on the existing heartbeat/Search card.
- Add focused tests under `src/test/java`; extend existing dirty tests only when their current whole-file preimage is revalidated.

---

### Task 1: Ownership, Three-Way Preflight, and Lease

**Files:**
- Modify: `data/agent-handoff/codex/ownership/provider-integration-20260818.json`

- [ ] Recompute the manifest path set, Git blobs, SHA-256 values, branch, HEAD, index-lock state, active sourceSets, PatchDrop top-level count, and source-edit lease count.
- [ ] Freeze one redacted EvidenceSnapshot with at most 20 rows and one SHA-256 hash.
- [ ] Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY` over that snapshot. Require identical scenario IDs, stable A-B/B-A `APPLY`, score at least 50, declared verification, no safety failure, and `nextWorkflow=existing-source-owner-guard`.
- [ ] Acquire the existing lease:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action begin -Role desktop -Root . -Topic provider-integration-20260818 -OwnerId codex-provider-integration-20260818 -TtlMinutes 540
```

- [ ] Update the manifest to `owned-postimages-verified`, record the snapshot hash, stable verdict, score, and matching lease. Mutation remains restricted to declared paths.

### Task 2: Central Provider Credential Resolution

**Files:**
- Create: `main/java/com/example/lms/guard/ProviderCredentialResolver.java`
- Create: `src/test/java/com/example/lms/guard/ProviderCredentialResolverTest.java`
- Modify: `main/java/com/example/lms/guard/KeyResolver.java`
- Modify narrowly: active Brave/NAVER/Tavily/SerpAPI credential consumers and health projection.

- [ ] Write RED parameterized tests for missing/dummy values, one alias, two and three equal aliases, distinct alias conflict, preserved precedence, NAVER pair/CSV forms, and secret-free diagnostics for Gemini, OpenAI, local-compatible, Brave, NAVER, Tavily, and SerpAPI.
- [ ] Run the new resolver test and confirm it fails because the resolver is absent.
- [ ] Implement immutable `Resolution` and `Diagnostics` values. Filter sentinels using `ConfigValueGuards`; compare normalized values without logging them; emit only provider/source/count/present/reason.
- [ ] Make `KeyResolver` a compatibility adapter. Equal duplicates return the existing effective value; conflicts return null/empty and mark only that provider disabled. Preserve all public methods and aliases.
- [ ] Route active provider clients through the resolver with fail-soft disabled reasons. Preserve existing source-local trace keys expected by tests.
- [ ] Run resolver and existing key/provider tests:

```powershell
.\gradlew.bat test --tests 'com.example.lms.guard.ProviderCredentialResolverTest' --tests 'com.example.lms.guard.KeyResolverProviderKeyTest' --tests 'com.example.lms.service.search.NaverCredentialBridgeTest' --tests 'com.example.lms.search.WebProviderTraceReasonsTest'
```

### Task 3: One Gemini Gateway and Supported Models

**Files:**
- Create: `main/java/com/example/lms/learning/gemini/GeminiGateway.java`
- Create: `src/test/java/com/example/lms/learning/gemini/GeminiGatewayContractTest.java`
- Modify: `main/java/com/example/lms/learning/gemini/GeminiClient.java`
- Modify: `main/java/com/example/lms/client/GeminiClientDecorator.java`
- Modify: `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`
- Modify: `main/java/com/example/lms/service/rag/pre/LongInputDistillationService.java`
- Modify: `main/java/com/example/lms/llm/ModelMap.java`
- Modify: `main/resources/application.yml`
- Modify: `main/resources/application-llm.yaml`

- [ ] Write RED tests with a fake WebClient exchange: disabled/missing/conflicting credential performs zero exchange; configured model is used; each purpose is independently gated; timeout/retry/breaker/quota are bounded; model preflight is cached; telemetry contains only the 12 allowed fields.
- [ ] Implement `GeminiGateway` with `Purpose` values for search expansion, translation, understanding/generation, keyword/training, curation, and router. Own native request URIs, model preflight, max attempts, Reactor timeout/retry/circuit breaker, optional free-tier throttle, and status projection.
- [ ] Turn `GeminiClient` into a compatibility facade. Preserve current public signatures and fail-soft return shapes, but remove every direct WebClient call and key lookup.
- [ ] Remove `GeminiClientDecorator` as a competing Spring bean/wire owner; gateway policy replaces it.
- [ ] Delegate Gemini OpenAI-compatible model construction in `LlmRouterAspect` to the gateway. Keep response-model verification and router telemetry wrappers. When router is off or gateway/credential is unavailable, perform zero Gemini exchange.
- [ ] Replace all active `gemini-1.5-flash` references with configured supported 2.5 defaults. Do not alter the existing `gemini-2.5-pro` route solely for a false shutdown claim.
- [ ] Run focused gateway/facade/router tests and the exact static gate:

```powershell
.\gradlew.bat test --tests 'com.example.lms.learning.gemini.GeminiGatewayContractTest' --tests 'com.example.lms.learning.gemini.GeminiClientRedactionContractTest' --tests 'ai.abandonware.nova.orch.aop.LlmRouterGatewaySecurityTest'
rg -n 'gemini-1\.5-flash' main/java main/resources
```

Expected static result: no output and exit code 1 from `rg`.

### Task 4: Bounded Zero-Result Search Recovery

**Files:**
- Create: `src/test/java/com/example/lms/search/provider/HybridWebSearchProviderBoundedFallbackTest.java`
- Modify: `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java`
- Modify: `main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java`

- [ ] Write RED call-count tests for: first Brave hit; Brave empty then NAVER hit; both empty then local rewrite plus one Gemini expansion plus one second provider cycle; disabled/blank/duplicate/failing Gemini; second-cycle empty; and second-cycle hit. Assert Brave and NAVER each at most twice, Gemini exactly zero or one, and no third cycle/recursion.
- [ ] Add a request-local bounded state/result value in `HybridWebSearchProvider`. Reuse the existing active Brave/NAVER calls and `QueryTransformer` for local rewriting; choose one nonblank, nonduplicate rewritten query. Call `GeminiGateway.expandSearchQueryOnce` only after the local result still has zero evidence.
- [ ] Mark bounded route completion with count-only TraceStore fields. For this invocation, bypass soak, live backup, trace-expanded-Brave, and any network remerge path.
- [ ] Add a RED aspect test and then make `WebFailSoftSearchAspect` skip its min-citation, quality, and staged-empty `pjp.proceed` rescues when the bounded route marker is present.
- [ ] Run focused tests:

```powershell
.\gradlew.bat test --tests 'com.example.lms.search.provider.HybridWebSearchProviderBoundedFallbackTest' --tests 'ai.abandonware.nova.orch.aop.WebFailSoftSearchAspectTest'
```

### Task 5: Exact Provider Health Projection

**Files:**
- Create: `src/test/java/com/example/lms/agent/context/ProviderStatusProjectionTest.java`
- Modify: `main/java/com/example/lms/agent/context/AgentPipelineHealthController.java`
- Modify: `main/java/com/example/lms/web/ChatUiHeartbeatPayload.java`
- Modify: `main/java/com/example/lms/web/ChatUiCoreHeartbeatProbe.java`
- Modify: `main/resources/static/js/chat.js`

- [ ] Write RED tests seeding provider trace values plus decoy raw key/header/prompt/query fields. Assert each status row has exactly 12 allowed keys, numeric values are bounded, missing evidence is `not_observed`, and decoy strings never survive.
- [ ] Add a field-specific projection; never pass a generic TraceStore map through the heartbeat sanitizer. Preserve existing `webProviders`, `providerRuntime`, and `failSoftLadder` compatibility.
- [ ] Add `providerStatus` to the heartbeat top-level allowlist and a deterministic unavailable/not-observed fallback in the core probe.
- [ ] Render only a compact provider summary in the existing Search heartbeat card using `textContent`; add no endpoint or raw debug panel.
- [ ] Run health/payload/frontend tests:

```powershell
.\gradlew.bat test --tests 'com.example.lms.agent.context.ProviderStatusProjectionTest' --tests 'com.example.lms.agent.context.AgentPipelineHealthControllerTest' --tests 'com.example.lms.web.ChatUiHeartbeatPayloadTest' --tests 'com.example.lms.web.ChatFrontendSecurityTest'
```

### Task 6: Configuration, Static Proof, and Provider Smokes

**Files:**
- Modify: `main/resources/application.yml`
- Modify: `main/resources/application-llm.yaml`

- [ ] Add `gemini.gateway` settings for enabled, base URLs, 2.5 model defaults by purpose, timeout, max attempts, breaker, preflight, throttle, and per-purpose enablement. Search expansion is on; every other purpose and the global router remain off for the first verification.
- [ ] Verify every preserved provider alias by a focused resolver test; do not persist or print credential values.
- [ ] Run one bounded Brave smoke and one bounded NAVER smoke only when the corresponding credential resolution is present and nonconflicting. Run one Gemini model preflight only when its credential is present and nonconflicting. Record only provider/status/statusCode/latency/reason and call counts; otherwise record `NO_CREDENTIAL` or `DISABLED` with zero wire attempts.
- [ ] Confirm no Supabase runtime additions and no secret-bearing source/log/NDJSON hits:

```powershell
rg -n '(supabase-java|SUPABASE_(ANON|SERVICE_ROLE|SECRET)_KEY|create table|enable row level security)' build.gradle.kts main/java main/resources
rg -n 'gemini-1\.5-flash' main/java main/resources
```

### Task 7: Broad Verification and Browser Proof

- [ ] Re-run exact frozen preimage preservation checks for every preserve-only file and inspect `git diff --check` plus path-scoped diffs.
- [ ] Run the repository verification ladder with isolated Desktop outputs/cache:

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='desktop-provider-integration'
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle-awx-desktop-provider-integration"
.\gradlew.bat --project-cache-dir .gradle-provider-integration projects compileJava test :app:classes bootJar
```

- [ ] Start only a task-owned Spring process on an unoccupied non-8080 port, prove listener lineage and fresh artifact/source hashes, then verify `/api/chat/ui-heartbeat` and `/chat-ui` without stopping the unrelated 8080 listener.
- [ ] In the in-app Browser, open the exact task-owned `/chat-ui`, expand Diagnostics, and verify the Search card visibly shows redacted provider health. Capture console/network proof that no key/header/raw prompt is exposed and the chat UI still submits/cancels normally.
- [ ] Release the lease in all completion/stop paths:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action end -Role desktop -Root . -Topic provider-integration-20260818 -OwnerId codex-provider-integration-20260818
```

- [ ] Report actual test/build/runtime/browser/provider results, call counts, secret-scan counts, path-scoped diff, and any exact `evidence_needed` item. Do not claim `REACHABLE` without an observed provider wire attempt.
