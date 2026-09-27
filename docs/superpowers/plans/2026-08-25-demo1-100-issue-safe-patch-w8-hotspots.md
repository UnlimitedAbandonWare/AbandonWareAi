# Demo-1 W8 Structural Hotspot Disposition Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close IDs 89-100 without wholesale rewrites by extracting only a W1-W7-touched responsibility that has a focused behavioral contract, proving a hotspot claim stale, or recording an exact isolated `HOLD`.

**Architecture:** W8 is a constrained postprocess, not a second refactor project. Each candidate is evaluated only after W1-W7 dispositions are known. A structural patch must reduce responsibility, branch/catch concentration, or dependency fan-in on the already-touched behavior while preserving the same public contract. File-length reduction alone is neither the trigger nor acceptance proof.

**Tech Stack:** Java 17, existing Spring components/interfaces, JUnit 5, AssertJ, repository score/health tools, Gradle Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-25-demo1-100-issue-safe-patch-design.md`

## Global Constraints

- No broad class rewrite, package move, public API redesign, duplicate owner, dependency addition, formatting sweep, or speculative abstraction.
- A structural extraction is eligible only when W1-W7 changed the same responsibility and the focused RED/GREEN test already defines behavior.
- Move code without changing semantics first; any further behavior change belongs to its original W1-W7 cohort and must be reverified there.
- New helper names describe one responsibility, not a generic `Manager`, `Utils`, `Helper`, or framework.
- Preserve Spring bean identity unless extraction explicitly introduces a narrow collaborator. Compatibility aliases never become components.
- Run a separate source preflight and preimage check for each hotspot edit. Use `demo1-subsystem-patch-directive` only for S01-S08 algorithm bodies and `demo1-cross-subsystem-guard` only if the accepted final diff crosses its trigger.
- `NO_PATCH_NEEDED` requires measured current evidence that the original concentration claim is stale. “Not touched in this run” alone results in `HOLD`, not `NO_PATCH_NEEDED`.

## Coverage

| ID | Active owner | Candidate W1-W7 seam | Nearest focused test |
|---:|---|---|---|
| 89 | `ChatWorkflow` | cancellation-to-terminal-side-effect sequence | `ChatWorkflowCancellationContractTest` |
| 90 | `WebFailSoftSearchAspect` | deadline/await-event normalization | `WebFailSoftSearchAspectTest` |
| 91 | `NaverSearchService` | top-K/deadline/shared-waiter policy | `NaverSearchServiceInterruptContractTest` |
| 92 | `ChatApiController` | session delete/exact-run cancellation | `ChatApiControllerCancelTest` |
| 93 | `HybridWebSearchProvider` | deadline-bounded await/fallback | `HybridWebSearchProviderBoundedFallbackTest` |
| 94 | `UnifiedRagOrchestrator` | retrieval-result final trim/projection | `UnifiedRagOrchestratorRagEvalTest` |
| 95 | `AgentPipelineHealthController` | heartbeat projection/cache | `AgentPipelineHealthControllerTest` |
| 96 | `HybridRetriever` | finalization/top-K consistency | `HybridRetrieverImplicitConsistencyTest` |
| 97 | `EvidenceAwareGuard` | pure evidence decision vs debug emission | `EvidenceAwareGuardFailSoftBreadcrumbTest` |
| 98 | `ModelRuntimeHealthTracker` | route key and promotion policy | `ModelRuntimeHealthTrackerTest` |
| 99 | `SelfAskWebSearchRetriever` | bounded search-attempt lifecycle | `SelfAskWebSearchRetrieverTest` |
| 100 | `DynamicContextCompressor` | prompt-composition memory gate | `DynamicContextCompressorTest` |

---

### Task 0: Measure each hotspot and bind it to actual W1-W7 diffs

**Files:** The 12 active owners above, their nearest tests, W1-W7 final diffs, and the terminal ledger.

**Interfaces:**

- Consumes: pre/post source hashes, touched methods, call sites, method lengths, branch/catch counts, constructor dependencies, and focused test ownership.
- Produces: one disposition trigger packet per ID.

- [x] **Step 1:** Reconfirm every path against active source sets and Git canonical casing. Reject similarly named aliases/reference copies.
- [x] **Step 2:** For each file, record total lines, largest touched-method lines, touched-method branch/catch count, constructor dependency count, and number of independent side-effect categories. Store numbers only in the ledger.
- [x] **Step 3:** Bind each ID to exact W1-W7 changed hunks and focused green tests. Mark `touchOverlap=true|false`.
- [x] **Step 4:** Choose exactly one disposition:

  - `EXTRACT`: touched seam has at least two separable responsibilities, a stable focused test, no overlapping dirty hunk, and the extraction fits the remaining budget.
  - `NO_PATCH_NEEDED`: the current active file/method no longer satisfies the documented concentration claim, with measured evidence.
  - `HOLD`: no touch overlap, ambiguous owner, overlapping hunk, insufficient test boundary, or extraction exceeds the remaining budget.

- [x] **Step 5:** For every `HOLD`, populate exactly:

  ```text
  holdScope=<class#method responsibility>
  firstBlockingRule=<no-touch-overlap|dirty-hunk-overlap|test-boundary-missing|budget-insufficient|owner-ambiguous>
  blockingEvidence=<path + hash + metric/test evidence>
  independentWorkCompleted=<W1-W7 disposition and verification>
  repositoryWideHold=false
  nextAction=<one isolated extraction/test command>
  ```

---

### Task 1: Extract only the proven chat terminal/session lifecycle seams

**Files:**

- ID 89 candidate: `main/java/com/example/lms/service/ChatWorkflow.java`.
- ID 89 test: `src/test/java/com/example/lms/service/ChatWorkflowCancellationContractTest.java`.
- ID 92 candidate: `main/java/com/example/lms/api/ChatApiController.java`.
- ID 92 test: `src/test/java/com/example/lms/api/ChatApiControllerCancelTest.java`.
- Create only when eligible: `main/java/com/example/lms/service/chat/ChatTerminalSideEffectCoordinator.java`.
- Create only when eligible: `main/java/com/example/lms/service/chat/SessionRunLifecycleService.java`.

**Interfaces:**

- Consumes: W5 ID 30/48 final green behavior and exact-run cancellation context.
- Produces: a package-narrow coordinator/operation with unchanged public controller/workflow behavior.

- [x] **Step 1: Evaluate ID 89 trigger**

  Extract only if W5 changed multiple cancellation checks plus verifier/learning/memory/persistence sequencing inside `continueChat`. The candidate contract is:

  ```java
  interface ChatTerminalSideEffectCoordinator {
      TerminalOutcome complete(ChatRunExecutionContext run, TerminalInputs inputs);
  }
  ```

  Inputs contain typed collaborators/data already available; they do not contain a raw service locator, request, or mutable global context. The coordinator checks cancellation before each side-effect phase.

- [x] **Step 2: Perform behavior-preserving ID 89 extraction**

  First move the already-green terminal segment without semantic changes. Keep prompt building and generation in `ChatWorkflow`. Re-run `ChatWorkflowCancellationContractTest` after each move. Accept only if the touched `continueChat` segment loses a distinct side-effect responsibility or material branch/catch concentration.

- [x] **Step 3: Evaluate ID 92 trigger**

  Extract only if W5 ID 30 added cancel-before-delete orchestration in `ChatApiController`. Candidate operation:

  ```java
  public DeleteSessionResult deleteAuthorizedSession(Long sessionId, OwnerIdentity owner) {
      // authorize -> fence/cancel exact run -> delete history -> fixed result
  }
  ```

  Keep HTTP binding/status mapping in the controller. Do not move unrelated streaming, restore, heartbeat, or session-list logic.

- [x] **Step 4: Run focused verification**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.ChatWorkflowCancellationContractTest" --tests "com.example.lms.api.ChatApiControllerCancelTest" --no-daemon
  ```

- [x] **Step 5:** Record pre/post touched-method metrics and disposition for IDs 89/92. If either trigger is false, write the exact `HOLD` packet instead of creating a helper.

---

### Task 2: Extract only the provider/retrieval policy seams proven by W3/W4/W6

**Files and per-ID boundaries:**

- ID 90: `main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java`; test `src/test/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspectTest.java`; candidate immutable await-summary/trace projector from `aroundSearch`, `aroundSearchWithTrace`, `normalizeAwaitEventsForOps`, and `summarizeAwaitEventsForTrace`.
- ID 91: `main/java/com/example/lms/service/NaverSearchService.java`; tests `NaverSearchServiceInterruptContractTest` and `NaverSearchServiceFailureClassContractTest`; candidate waiter-local request policy/result projector, not provider I/O.
- ID 93: `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java`; test `HybridWebSearchProviderBoundedFallbackTest`; candidate monotonic deadline-bounded await/fallback policy from `searchBoundedFallback`/`awaitWithDeadline`.
- ID 94: `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`; tests `UnifiedRagOrchestratorRagEvalTest` and `UnifiedRagOrchestratorProviderTraceIntegrationTest`; candidate pure final result trim/partial-outcome projector after `retrieveCandidates`/`fuseRrf`.
- ID 96: `main/java/com/example/lms/service/rag/HybridRetriever.java`; test `HybridRetrieverImplicitConsistencyTest`; candidate pure `finalizeResults`/top-K helper.
- ID 99: `main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java`; test `SelfAskWebSearchRetrieverTest`; candidate bounded attempt adapter around `submitSearchAttempt`, `getWithHardTimeout`, and `cancelSearchAttempts`.

**Interfaces:**

- Consumes: final green tests from W3/W4/W6 and actual touched-method overlap.
- Produces: zero or more one-responsibility pure/policy collaborators, each accepted independently.

- [x] **Step 1: Evaluate each ID independently**

  No extraction is triggered by another file in this group. Require an actual W3/W4/W6 changed hunk in the named seam and a passing focused test that would fail on semantic drift.

- [x] **Step 2: Prefer immutable value/result types over new Spring beans**

  Suitable shapes include:

  ```java
  record AwaitSummary(int completed, int unfinished, boolean deadlineExceeded, String reason) {}
  record RetrievalFinalization<T>(List<T> results, int requestedTopK, int unfinishedCount) {}
  ```

  Keep types package-private when no cross-package caller needs them. Do not introduce a universal deadline, provider, or retrieval framework.

- [x] **Step 3: Move one seam at a time**

  Preserve current trace keys, ordering, timeout reason codes, exception taxonomy, and public return types. After each move, run only that owner's focused test before touching the next ID.

  ```powershell
  .\gradlew.bat test --tests "ai.abandonware.nova.orch.aop.WebFailSoftSearchAspectTest" --no-daemon
  .\gradlew.bat test --tests "com.example.lms.service.NaverSearchServiceInterruptContractTest" --tests "com.example.lms.service.NaverSearchServiceFailureClassContractTest" --no-daemon
  .\gradlew.bat test --tests "com.example.lms.search.provider.HybridWebSearchProviderBoundedFallbackTest" --no-daemon
  .\gradlew.bat test --tests "com.example.lms.service.rag.orchestrator.UnifiedRagOrchestratorRagEvalTest" --tests "com.example.lms.service.rag.orchestrator.UnifiedRagOrchestratorProviderTraceIntegrationTest" --no-daemon
  .\gradlew.bat test --tests "com.example.lms.service.rag.HybridRetrieverImplicitConsistencyTest" --no-daemon
  .\gradlew.bat test --tests "com.example.lms.service.rag.SelfAskWebSearchRetrieverTest" --no-daemon
  ```

- [x] **Step 4:** Record pre/post responsibility/branch/fan-in metrics for every accepted extraction. IDs without eligible overlap receive separate `HOLD` packets; do not combine them into one blanket hold.

---

### Task 3: Extract only the proven heartbeat, evidence, health, and compressor seams

**Files and per-ID boundaries:**

- ID 95: `main/java/com/example/lms/agent/context/AgentPipelineHealthController.java`; test `src/test/java/com/example/lms/agent/context/AgentPipelineHealthControllerTest.java`; candidate public heartbeat snapshot projector/cache around `pipelineHealth`, `providerStatus`, `latestProviderStatusTraceSnapshot`, and `modelRuntime` only if W7 proves this controller is on the public heartbeat call path.
- ID 97: `main/java/com/example/lms/service/guard/EvidenceAwareGuard.java`; test `src/test/java/com/example/lms/service/guard/EvidenceAwareGuardFailSoftBreadcrumbTest.java`; candidate pure coverage/degradation decision separated from debug emission only if W5 cancellation reaches this guard after cancellation.
- ID 98: `main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java`; test `src/test/java/com/example/lms/llm/ModelRuntimeHealthTrackerTest.java`; candidate immutable route-health key and semantic promotion policy from `recordSuccess`, `recordFailure`, and `isPromotable`.
- ID 100: `main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java`; test `src/test/java/ai/abandonware/nova/orch/compress/DynamicContextCompressorTest.java`; candidate prompt-composition memory-input gate from `composeForPrompt`/`compressMemoryForPrompt` only if W5 ID 13 proves it reads EPHEMERAL history.

**Interfaces:**

- Consumes: W5/W7 call-path evidence and focused green tests.
- Produces: eligible narrow policy/projector values or exact lane-local holds.

- [x] **Step 1: Resolve ID 95 call path before any edit**

  If `/ui-heartbeat` uses only `ChatUiHeartbeatController`/`ChatUiCoreHeartbeatProbe`, ID 95 has no W7 touch overlap and must be `HOLD` unless current metrics prove the original `AgentPipelineHealthController` claim stale. Do not move private/admin diagnostics merely to satisfy the row.

- [x] **Step 2: Resolve IDs 97 and 100 from W5 evidence**

  If cancellation stops before `EvidenceAwareGuard`, or EPHEMERAL passes empty memory before `DynamicContextCompressor`, these classes need no behavioral patch. Record `HOLD` for structural work unless measured current evidence supports `NO_PATCH_NEEDED`.

- [x] **Step 3: Evaluate ID 98 extraction after W5 health tests**

  Prefer immutable values such as:

  ```java
  record RouteHealthKey(String provider, String endpointHash, String model, String context) {}
  record SemanticPromotion(boolean eligible, String reason) {}
  ```

  Extract normalization/equality/promotion decisions only. Keep request timeline retention and public snapshots in the existing owner.

- [x] **Step 4: Run focused tests for every accepted move**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.agent.context.AgentPipelineHealthControllerTest" --tests "com.example.lms.service.guard.EvidenceAwareGuardFailSoftBreadcrumbTest" --tests "com.example.lms.llm.ModelRuntimeHealthTrackerTest" --tests "ai.abandonware.nova.orch.compress.DynamicContextCompressorTest" --no-daemon
  ```

- [x] **Step 5:** Record one disposition and metric packet per ID 95, 97, 98, and 100.

---

### Task 4: Verify structural changes preserve behavior and actually reduce concentration

**Files:** Every W8 accepted extraction, W1-W7 affected tests, and W8 ledger rows.

- [x] **Step 1:** Re-run each changed owner's focused tests plus the originating W1-W7 acceptance tests. Do not rely on compile-only evidence.
- [x] **Step 2:** Run `compileJava`, source-set hygiene, LangChain4j version purity, and the structural score/report command used at Task 0.
- [x] **Step 3:** Compare pre/post metrics. `PATCHED` requires at least one of:

  - one coherent side-effect responsibility moved from the hotspot;
  - touched-method branch/catch concentration reduced;
  - constructor/dependency fan-in for the touched behavior reduced;
  - pure policy/projector separated with direct focused tests.

- [x] **Step 4:** Reject an extraction that merely moves lines, adds delegation without reducing responsibility, duplicates state, expands a public API, changes trace/result semantics, or raises dependency fan-in.
- [x] **Step 5:** Inspect final diffs for aliases becoming beans, circular dependencies, generic helper names, public mutable collections, unrelated formatting, and copied logic remaining in both locations.

---

### Task 5: Close all 12 W8 rows

**Files:** Terminal ledger and non-staged evidence summary.

- [x] **Step 1:** Confirm IDs 89-100 each have exactly one final disposition.
- [x] **Step 2:** For `PATCHED`, record old/new file and method metrics, test command, exit code, and source hash.
- [x] **Step 3:** For `NO_PATCH_NEEDED`, record the current metric/call-path evidence that makes the original hotspot claim stale.
- [x] **Step 4:** For `HOLD`, record the required six-field packet from Task 0 and one concrete next isolated test/extraction action.
- [x] **Step 5:** Set `repositoryWideHold=false` for every ordinary W8 hold; a single structural hotspot cannot block already-proven independent W1-W7 work.
- [x] **Step 6:** Record a no-commit checkpoint. No commit, push, deploy, broad formatting, or external-system mutation is authorized.
