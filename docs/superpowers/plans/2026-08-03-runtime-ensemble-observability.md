# Runtime Ensemble Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove one three-candidate-to-neutral-judge request with redacted lineage, per-call monotonic latency, and provider-observed judge token usage without estimating missing usage.

**Architecture:** Keep Java and the existing `TraceStore` request context as the authority. Derive only hashes from parent correlation values, install a private context per ensemble worker, merge only `ensemble.node.<role>.*` fields, and record provider usage directly from LangChain4j 1.0.1 `ChatResponse.tokenUsage()`. Reuse existing admin trace/diagnostic transport unless a RED contract proves it drops the new allowlisted fields.

**Tech Stack:** Java 17, Spring Boot/Gradle, LangChain4j 1.0.1, JUnit 5, existing `PromptContext -> PromptBuilder.build(...)`, existing admin diagnostics/browser surfaces.

## Global Constraints

- Active root sourceSet only: `main/java`, `main/resources`, `src/test/java`; do not modify inactive mirrors.
- Preserve every `dev.langchain4j` dependency at exactly `1.0.1`.
- Do not expose raw prompts, queries, request IDs, provider responses, credentials, headers, or environment values.
- Do not estimate token usage. Missing provider usage is `observed=false` plus `provider_usage_unavailable`.
- Keep exactly three candidate roles: `support`, `support_alternative`, `falsify`; neutral adjudication is not majority voting.
- Preserve final prompt construction on `PromptBuilder.build(PromptContext)`.
- No database or Supabase mutation, no public API, no new always-on Python service, and no arbitrary endpoint/model/key controls.
- Stop on index lock, PatchDrop pending, changed target preimage, lease collision, target dirty-owner ambiguity, failed focused tests, or secret-scan risk.
- Because canonical `main` is already dirty, do not stage or commit until a source-owner guard names an isolated branch/worktree or an atomic target bundle.

---

### Task 1: Freeze the ownership and runtime evidence gate

**Files:**
- Read: `build.gradle.kts`
- Read: `settings.gradle.kts`
- Read: `main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java`
- Read: `main/java/com/example/lms/ensemble/EnsembleJudgeService.java`
- Read: `src/test/java/com/example/lms/ensemble/DiverseSamplingOrchestratorTest.java`
- Read: `src/test/java/com/example/lms/ensemble/EnsembleJudgeServiceTest.java`

**Interfaces:**
- Consumes: current Git/worktree/index/PatchDrop/lease state and SHA-256 preimages.
- Produces: one redacted `EvidenceSnapshot`, order-stable `APPLY | HOLD | REJECT`, and an explicit source owner.

- [ ] **Step 1: Record exact target preimages and conflict gates**

Run PowerShell read-only checks for worktrees, branch, target-only status, `.git/index.lock`, top-level PatchDrop patches, active leases, ports 8080/8081, and SHA-256 for the four target files.

- [ ] **Step 2: Run independent evidence roles**

Give the same frozen evidence to `SUPPORT_CONTRACT`, `SUPPORT_SCENARIO`, and `FALSIFY`. Give only those packets plus current command evidence to `NEUTRAL`; run packet order A-B and B-A. Any changed verdict is `HOLD`.

- [ ] **Step 3: Enter the source-owner guard only on stable APPLY**

Use `demo1-source-edit-three-way-preflight`. The permitted target set is exactly the two production files and their two tests. Any other source edit requires a new preflight.

### Task 2: Preserve redacted parent lineage and measure node calls

**Files:**
- Modify: `main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java`
- Test: `src/test/java/com/example/lms/ensemble/DiverseSamplingOrchestratorTest.java`

**Interfaces:**
- Consumes: `TraceStore.context()`, node `NodeSpec`, the final sampling prompt, and bounded model options.
- Produces: `ensemble.node.<role>.requestHash`, `.traceHash`, `.promptHash`, `.optionsHash`, and `.modelCallElapsedMs`; values are hashes/counts only.

- [ ] **Step 1: Write the failing worker-lineage test**

Add a JUnit test that installs parent values `requestId=request-123`, `traceId=trace-456`, and `rawQuery=must-not-copy`; runs the three-role sampler with recording models; then asserts all three role prefixes contain `requestHash`, `traceHash`, `promptHash`, `optionsHash`, and a non-negative `modelCallElapsedMs`. Assert no role prefix contains `request-123`, `trace-456`, or `must-not-copy`.

- [ ] **Step 2: Run the single test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.example.lms.ensemble.DiverseSamplingOrchestratorTest.workerTraceCarriesOnlyHashedLineageAndModelLatency" --rerun-tasks --fail-fast --no-daemon --project-cache-dir "$env:LOCALAPPDATA\awx-project-cache-desktop-ensemble-observability"
```

Expected: FAIL because the worker starts from an empty `ConcurrentHashMap` and no `modelCallElapsedMs` is recorded.

- [ ] **Step 3: Implement the minimal lineage context**

Replace direct `new ConcurrentHashMap<>()` construction with a helper that creates a new map and writes only:

```java
String prefix = "ensemble.node." + spec.id() + ".";
putHash(worker, prefix + "requestHash", parent, "requestId", "x-request-id", "requestIdHash");
putHash(worker, prefix + "traceHash", parent, "traceId", "trace.id", "traceIdHash");
```

`putHash` must choose the first nonblank scalar and store `SafeRedactor.hashValue(String.valueOf(value))`; it must never copy the source key/value itself. In `prepareNode`, store `promptHash` from the final `samplingPrompt` and `optionsHash` from the bounded model name/temperature/topP/maxTokens/timeout tuple.

- [ ] **Step 4: Measure the actual model call in a finally block**

Wrap only `prepared.model().chat(...)` with `System.nanoTime()` and always write:

```java
long elapsedMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
TraceStore.put(tracePrefix + "modelCallElapsedMs", elapsedMs);
```

Do not mix factory/preparation, queue wait, scoring, or UI latency into this value.

- [ ] **Step 5: Run the focused class and verify GREEN**

Run the new test first, then the complete `DiverseSamplingOrchestratorTest` class with `--rerun-tasks --fail-fast`.

### Task 3: Record provider-observed neutral-judge usage

**Files:**
- Modify: `main/java/com/example/lms/ensemble/EnsembleJudgeService.java`
- Test: `src/test/java/com/example/lms/ensemble/EnsembleJudgeServiceTest.java`

**Interfaces:**
- Consumes: LangChain4j 1.0.1 `ChatResponse.tokenUsage()` and `TokenUsage.inputTokenCount()`, `outputTokenCount()`, `totalTokenCount()`.
- Produces: `ensemble.judge.tokenUsageObserved`, optional non-negative `.inputTokens/.outputTokens/.totalTokens`, `.tokenUsageReason`, and `.modelCallElapsedMs`.

- [ ] **Step 1: Write provider-usage RED tests**

Add one recording model response built with `new TokenUsage(11, 7, 18)` and assert the exact three counts plus `tokenUsageObserved=true`. Add a second response without usage and assert `tokenUsageObserved=false`, `tokenUsageReason=provider_usage_unavailable`, and all three count keys absent. Neither test may infer counts from prompt or response length.

- [ ] **Step 2: Run the two tests and verify RED**

Run both named methods with the same isolated Gradle cache. Expected: FAIL because the service currently reads only `aiMessage()` and text.

- [ ] **Step 3: Implement usage projection and latency**

Keep the existing `ChatResponse` object. After the call, inspect `response.tokenUsage()`. Mark observed only when input, output, and total are all present and non-negative; copy that complete provider-returned triplet exactly. Null usage, an all-null usage object, any partial triplet, or any negative count must omit every count key and write the stable unavailable reason. Never fill or sum missing fields. Measure only `model.chat(...)` with `System.nanoTime()` in a `finally` block.

- [ ] **Step 4: Run the focused class and verify GREEN**

Run the two new tests, then all `EnsembleJudgeServiceTest` tests. Confirm cancellation behavior and fail-closed HOLD behavior remain unchanged.

### Task 4: Prove existing admin transport or stop before UI expansion

**Files:**
- Read first: `main/java/com/example/lms/api/DebugAiMetricsController.java`
- Read first: `main/java/com/example/lms/debug/ai/DebugAiMetricsService.java`
- Read first: existing trace snapshot/SSE controllers and admin templates
- Modify only on RED: the narrow existing diagnostics projector and its test

**Interfaces:**
- Consumes: the new flat `TraceStore` keys.
- Produces: an authenticated, redacted view using an existing admin JSON/SSE boundary.

- [ ] **Step 1: Write a transport characterization test**

Seed only the new hashed/count/reason keys and assert the existing admin diagnostic response exposes them without raw parent identifiers. If the existing trace snapshot endpoint already passes this test, make no controller/template change.

- [ ] **Step 2: If RED, add the smallest allowlisted projection**

Project only role, hashes, elapsed milliseconds, observed usage/counts, status, and reason. Do not add a public route, control endpoint, WebSocket, provider input, environment mutation, or process launch.

- [ ] **Step 3: Verify access boundaries**

Run the focused security/controller tests proving ADMIN access, unauthenticated denial, and unchanged CSRF/session behavior.

### Task 5: One deterministic runtime/browser proof

**Files:**
- No source changes.
- Write only redacted/count-only verification evidence under the approved report path if the runtime already has such a path.

**Interfaces:**
- Consumes: one fixed chatbot request, an authenticated admin session, and the existing SSE/JSON trace surface.
- Produces: exactly three candidate rows and one judge row linked by the same request hash, or a precise `evidence_needed` reason.

- [ ] **Step 1: Run the focused and broad verification ladder**

Run `projects`, the two focused test classes, relevant diagnostics/security tests, `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, `compileJava`, `:app:classes`, and `bootJar` with `AWX_SPLIT_BUILD_OUTPUTS=1`, `AWX_BUILD_HOST_ID=desktop-ensemble-observability`, a dedicated `GRADLE_USER_HOME`, and a dedicated project cache.

- [ ] **Step 2: Boot exactly one Desktop runtime**

Do not parallelize boot tasks. Confirm source/JAR/served asset provenance before browser claims.

- [ ] **Step 3: Execute one fixed chatbot request**

Use three independent roles and a neutral judge. Confirm model attempts, exact role cardinality, the same request hash, non-negative call latency, observed judge usage or `provider_usage_unavailable`, and no raw prompt/credential material.

- [ ] **Step 4: Cross-check positive and negative browser scenarios**

Positive: provider returns usage and the admin view shows exact counts. Negative: provider omits usage and the view shows unavailable without estimates. Neutral: compare both packets in forward and reverse order and return `APPLY | HOLD | REJECT`.

- [ ] **Step 5: Final secret and diff review**

Run count-only secret scans and inspect the exact target diff. Do not stage unrelated dirty files. Commit only in the source-owner branch/worktree after every gate passes; otherwise leave the patch unstaged and report the blocker.

## Plan Self-Review

- Spec coverage: lineage, latency, observed usage, three roles, neutral adjudication, browser/admin proof, access control, fail-soft behavior, and rollback boundaries are covered.
- Placeholder scan: forbidden placeholder tokens and undefined implementation steps are absent.
- Type consistency: API names match the locally inspected LangChain4j 1.0.1 bytecode.
- Scope: database/Supabase mutation, public APIs, new provider controls, and unrelated prompt/postprocess work remain excluded.
