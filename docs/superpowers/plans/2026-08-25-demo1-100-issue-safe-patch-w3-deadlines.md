# Demo-1 W3 Deadlines, Cancellation, and Admission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close IDs 1, 2, 3, 4, 9, 10, 18, 21, 23, 29, 31, and 33-39 with one whole-operation deadline, truthful cancellation, bounded admission, and owned lifecycle cleanup.

**Architecture:** Existing request budgets remain authoritative. Search/graph/model/auxiliary work receives only remaining time and runs behind Spring-owned bounded executors or explicit leases; SSE uses one shared scheduler and cap; already-implemented model/local-process lifecycle contracts are characterized before any edit.

**Tech Stack:** Java 17 concurrency, Spring MVC/AOP/lifecycle, Reactor, JUnit 5, AssertJ, Awaitility/latches already in the repository, Gradle Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-25-demo1-100-issue-safe-patch-design.md`

## Global Constraints

- Use the addon request budget `com.abandonware.ai.addons.budget.TimeBudgetContext` and monotonic local deadlines; do not introduce another request budget model.
- Timeout code may request interruption, but ledger evidence distinguishes `caller_returned`, `task_cancel_requested`, and observed `worker_terminated|worker_unfinished`.
- No request-owned unbounded executor, common-pool blocking orchestration, sleep-based test, fake provider result, production dependency, commit, push, deploy, process termination, or real provider call.
- Never stop or signal a process not started by the focused test. Local LLM tests use fake owned capabilities only.
- Run executor/boot stress suites sequentially with isolated Gradle locations.
- Each changed owner receives a separate source preflight and immediate preimage recheck.

## Coverage

| IDs | Task | Active owner |
|---|---|---|
| 1, 2, 23 | 1 | `NovaAnalyzeWebSearchRetriever` |
| 3, 4, 29 | 2 | `HybridWebSearchProvider` |
| 9, 18 | 3 | `RagGraphExecutor`, `RagOrchestratorController` |
| 10 | 4 | `HarmonyScoreController` shared SSE runtime |
| 21, 31, 36, 37 | 5 | ensemble, timed model caller, query analysis |
| 33, 34, 35 | 6 | `LocalLlmProcessManager` characterization |
| 38, 39 | 7 | thumbnail and brain-state background work |

---

### Task 0: Freeze W3 executor, deadline, and process boundaries

**Files:** All task targets and adjacent executor configuration/tests.

**Interfaces:**

- Consumes: current `TimeBudgetContext`, executor beans, public endpoint mappings, and owned-process state.
- Produces: seven separately gated mutation/characterization cohorts.

- [ ] **Step 1:** Refresh branch/HEAD/sourceSet/index-lock/PatchDrop/lease/port evidence and target hashes. Record port owners read-only; do not stop them.
- [ ] **Step 2:** Run baseline tests for `NovaAnalyzeWebSearchRetrieverTimeoutTraceTest`, `HybridWebSearchProviderBoundedFallbackTest`, `RagGraphExecutorTest`, `HarmonyScoreControllerTest`, `TimedChatModelCallerTest`, `LocalLlmProcessManagerTest`, and `BrainStateChatWorkflowAspectTest`.
- [ ] **Step 3:** Freeze redacted scenarios `S_W3_NOVA_DEADLINE`, `S_W3_HYBRID_DEADLINE`, `S_W3_RAG_EXECUTOR`, `S_W3_HARMONY_SSE`, `S_W3_MODEL_ADMISSION`, and `S_W3_AUX_WORK`; require stable three-way `APPLY` only for cohorts that actually need source edits.

---

### Task 1: Bound planned Nova searches by request deadline and shared admission

**Files:**

- Modify: `main/java/ai/abandonware/nova/orch/adapters/NovaAnalyzeWebSearchRetriever.java`.
- Modify: `src/test/java/ai/abandonware/nova/orch/adapters/NovaAnalyzeWebSearchRetrieverTimeoutTraceTest.java`.

**Interfaces:**

- Consumes: `TimeBudgetContext.current()`, configured `timeoutMs`, shared `searchIoExecutor`, routing plan, fallback provider.
- Produces: one `deadlineNs`, bounded `SearchLease`, and stable routing-plan failure fallback.

- [ ] **Step 1: Add deadline/admission/plan-failure tests**

  ```java
  @Test
  void plannedAndOriginalFallbackShareOneDeadline() {
      budget(120);
      provider.blockEachAttemptFor(90);
      assertThat(measureMillis(() -> retriever.retrieve(query()))).isLessThan(220);
      assertThat(provider.attempts()).isLessThanOrEqualTo(2);
  }

  @Test
  void interruptIgnoringSearchesCannotGrowAcceptedOccupancy() throws Exception {
      SearchGate gate = gate(2);
      startTwoInterruptIgnoringCalls(gate);
      assertThat(retriever.retrieve(query())).isEmpty();
      assertThat(trace("nova.search.admission.reason")).isEqualTo("executor_saturated");
  }

  @Test
  void routingPlanExceptionUsesClassifiedOriginalFallback() {
      routingPlanThrows();
      assertThat(retriever.retrieve(query())).isEqualTo(originalFallbackResult());
      assertThat(trace("nova.search.plan.failureReason")).isEqualTo("routing_plan_failed");
  }
  ```

  Use latches and a fake monotonic budget; no sleeps or network.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetrieverTimeoutTraceTest" --no-daemon
  ```

- [ ] **Step 3: Reuse one deadline and add bounded lease**

  Compute `effectiveMs = min(timeoutMs, requestBudget.remainingMillis())` once and derive `deadlineNs`. Every plan, provider, poll, and original fallback receives `remainingMs(deadlineNs)`; no fallback receives a fresh full timeout. Add a small per-bean semaphore/lease sized to the shared executor's accepted parallelism, acquire before submit, release from worker `finally`, and fail soft with `executor_saturated` when unavailable. Never release a lease merely because the caller timed out while an interrupt-ignoring worker still runs.

  Catch routing-plan exceptions at the existing plan boundary, trace fixed class/reason, and invoke the original fallback only when remaining time is positive.

- [ ] **Step 4: Run GREEN and adapter/config tests**

  ```powershell
  .\gradlew.bat test --tests "ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetrieverTimeoutTraceTest" --tests "ai.abandonware.nova.autoconfig.Nova*Test" --no-daemon
  ```

- [ ] **Step 5:** Record ID 1 as owner; IDs 2/23 may merge only with individual passing tests and explicit shared-deadline/admission causes.

---

### Task 2: Make Hybrid search deadline and unfinished-work evidence truthful

**Files:**

- Modify: `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java`.
- Modify: `src/test/java/com/example/lms/search/provider/HybridWebSearchProviderBoundedFallbackTest.java`.
- Modify: `src/test/java/com/example/lms/search/provider/HybridWebSearchProviderAwaitEventRedactionTest.java`.

**Interfaces:**

- Consumes: request budget, Brave/Naver futures, cache-only remerge, fallback expansion.
- Produces: one request-clamped deadline and `WorkerOutcome(terminalReason,cancelRequested,workerFinished)` evidence.

- [ ] **Step 1: Add blocking-provider and interrupted-remerge RED tests**

  ```java
  @Test
  void blockingProvidersAndFallbackCannotExceedOneDeadline() {
      budget(150);
      blockBraveAndNaverIgnoringInterrupt();
      assertThat(measureMillis(() -> provider.search("safe", 5))).isLessThan(260);
      assertThat(trace("web.hybrid.workerTermination")).isEqualTo("unfinished");
  }

  @Test
  void cancellationSkipsCacheOnlySleepAndLaterExpansion() {
      Thread.currentThread().interrupt();
      try {
          assertThat(provider.search("safe", 5)).isEmpty();
          assertThat(cache.lookups()).isZero();
          assertThat(expander.calls()).isZero();
      } finally {
          Thread.interrupted();
      }
  }
  ```

  Add repeated-call admission proof: after the bounded accepted worker count is occupied, a new call returns classified fail-soft without submitting another provider task.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.search.provider.HybridWebSearchProviderBoundedFallbackTest" --tests "com.example.lms.search.provider.HybridWebSearchProviderAwaitEventRedactionTest" --no-daemon
  ```

- [ ] **Step 3: Enforce remaining time and lease lifecycle**

  Clamp the provider's local deadline to `TimeBudgetContext.current().remainingMillis()`. Pass only remaining time to block-timeout resolution, joins, expansion, and remerge. Replace request-thread polling sleep with a remaining-time-aware future/cache signal or return immediately on cancellation/deadline. Use bounded shared admission whose lease is released by worker completion, not caller timeout. Record `cancel_requested_no_interrupt`, `unfinished`, or `terminated`; never trace a claimed kill.

- [ ] **Step 4: Run GREEN and all Hybrid provider tests**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.search.provider.HybridWebSearchProvider*" --tests "ai.abandonware.nova.orch.aop.HybridWebSearch*Test" --no-daemon
  ```

- [ ] **Step 5:** Record IDs 3, 4, and 29 with distinct test evidence.

---

### Task 3: Remove common-pool graph work and bound public legacy RAG by the request budget

**Files:**

- Modify: `main/java/com/example/lms/config/SearchExecutorConfig.java`.
- Modify: `main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java`.
- Modify: `main/java/com/example/lms/api/RagOrchestratorController.java` only if endpoint characterization proves the facade can escape the request deadline.
- Modify: `src/test/java/com/example/lms/service/rag/langgraph/RagGraphExecutorTest.java`.
- Modify: `src/test/java/com/example/lms/api/RagOrchestratorControllerTest.java`.

**Interfaces:**

- Consumes: request budget and `RagOrchestratorFacade.query`.
- Produces: Spring-owned bounded `ragGraphExecutor` and deadline-aware endpoint result.

- [ ] **Step 1: Write executor identity, saturation, and endpoint deadline tests**

  ```java
  @Test
  void asyncGraphNeverRunsOnForkJoinCommonPool() {
      executor.executeAsync(request()).join();
      assertThat(capturedThreadName()).startsWith("awx-rag-graph-");
  }

  @Test
  void saturatedGraphExecutorFailsSoftWithoutQueuingUnboundedWork() {
      occupyWorkersAndQueue();
      assertThat(executor.executeAsync(request()).join().failureReason())
              .isEqualTo("graph_executor_saturated");
  }

  @Test
  void publicRagQueryReturnsWithinDeclaredBudgetAndCancelsWait() throws Exception {
      facadeBlocks();
      mvc.perform(post("/api/rag/query").header("X-Time-Budget-Ms", "100")
              .contentType(APPLICATION_JSON).content(safeQuery()))
              .andExpect(status().isRequestTimeout());
  }
  ```

- [ ] **Step 2: Run focused RED/characterization**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.rag.langgraph.RagGraphExecutorTest" --tests "com.example.lms.api.RagOrchestratorControllerTest" --no-daemon
  ```

- [ ] **Step 3: Inject a bounded graph executor and preserve request deadline**

  Define one `ThreadPoolTaskExecutor`/bounded executor bean in existing executor configuration with fixed workers, bounded queue, named daemon threads, and Spring shutdown. Inject it into `RagGraphExecutor` and pass it explicitly to `CompletableFuture.supplyAsync`. Rejected admission returns a fixed fail-soft graph result.

  For `/api/rag/query`, first prove whether the facade already observes `TimeBudgetContext`; if the blocking test is GREEN and worker cleanup is bounded, record ID 18 `NO_PATCH_NEEDED`. Otherwise execute via the same bounded graph/runtime boundary, wait only remaining time, cancel the wait, and return a fixed timeout response without claiming provider termination.

- [ ] **Step 4: Run GREEN and graph/RAG security tests**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.rag.langgraph.RagGraphExecutorTest" --tests "com.example.lms.api.RagOrchestratorControllerTest" --tests "com.example.lms.config.AppSecurityConfigContractTest" --no-daemon
  ```

- [ ] **Step 5:** Record IDs 9 and 18 independently.

---

### Task 4: Replace per-Harmony-stream schedulers with one capped shared runtime

**Files:**

- Create: `main/java/com/example/lms/harmony/HarmonySseRuntime.java`.
- Modify: `main/java/com/example/lms/harmony/HarmonyScoreController.java`.
- Modify: `src/test/java/com/example/lms/harmony/HarmonyScoreControllerTest.java`.
- Create: `src/test/java/com/example/lms/harmony/HarmonySseRuntimeTest.java`.

**Interfaces:**

- Consumes: `HarmonyScoreEngine.snapshot()` and SSE terminal callbacks.
- Produces: `Optional<StreamLease> open(Runnable tick)` capped at 32 and shared scheduled execution.

- [ ] **Step 1: Write cap and cleanup RED tests**

  ```java
  @Test
  void thirtyThirdStreamIsRejectedAndEveryTerminalCallbackReleasesOnce() {
      List<StreamLease> leases = open(32);
      assertThat(runtime.open(tick())).isEmpty();
      leases.get(0).close();
      assertThat(runtime.open(tick())).isPresent();
      leases.forEach(StreamLease::close);
      assertThat(runtime.activeCount()).isZero();
  }
  ```

  Controller tests cover completion, timeout, error, send failure, and application shutdown. No sleeps; use fake scheduler/futures and latches.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.harmony.HarmonyScoreControllerTest" --tests "com.example.lms.harmony.HarmonySseRuntimeTest" --no-daemon
  ```

- [ ] **Step 3: Implement shared scheduling and idempotent stream lease**

  `HarmonySseRuntime` owns one bounded scheduled pool, a 32-permit semaphore, and `@PreDestroy` shutdown. `StreamLease.close()` cancels its scheduled future and releases once via `AtomicBoolean`. Controller returns an immediately completed overload emitter/fixed 429-compatible error when no lease is available; every SSE callback closes the same lease.

- [ ] **Step 4:** Run GREEN plus `HarmonyScoreEngineTest` and template tests.
- [ ] **Step 5:** Record ID 10 with active-count-zero shutdown/terminal evidence.

---

### Task 5: Bound ensemble/query-analysis work and characterize the shared timed model caller

**Files:**

- Modify: `main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java`.
- Modify: `src/test/java/com/example/lms/ensemble/DiverseSamplingOrchestratorTest.java`.
- Read/modify only on RED: `main/java/com/example/lms/llm/TimedChatModelCaller.java`.
- Modify: `src/test/java/com/example/lms/llm/TimedChatModelCallerTest.java`.
- Modify: `main/java/com/example/lms/service/rag/query/QueryAnalysisService.java`.
- Modify: `src/test/java/com/example/lms/service/rag/query/QueryAnalysisResourceAllocationTest.java`.

**Interfaces:**

- Consumes: request budget, shared bounded model executors, cancellation state.
- Produces: bounded ensemble lease, caller-owned query `FutureTask`, and verified shared model recovery.

- [ ] **Step 1: Add repeated interrupt-ignoring and caller-interrupt tests**

  ```java
  @Test
  void repeatedInterruptIgnoringEnsemblesHitGlobalAdmissionInsteadOfGrowingThreads() {
      occupyAcceptedSamplingWorkers();
      assertThat(orchestrator.sample(request())).isEmpty();
      assertThat(samplingThreadCount()).isLessThanOrEqualTo(configuredMaximum());
  }

  @Test
  void queryAnalysisTimeoutInterruptsOwnedTaskAndNextTaskRuns() throws Exception {
      blockInterruptibly();
      assertThat(service.analyze("safe").fallbackReason()).isEqualTo("timeout");
      assertThat(workerExited()).isTrue();
      assertThat(submitSentinel()).isTrue();
  }
  ```

  Re-run existing `TimedChatModelCaller` tests with a one-worker/one-queue fixture proving four interrupt-ignoring calls do not permanently grow executor capacity and later cooperative work runs after release.

- [ ] **Step 2: Run focused RED/characterization**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.ensemble.DiverseSamplingOrchestratorTest" --tests "com.example.lms.llm.TimedChatModelCallerTest" --tests "com.example.lms.service.rag.query.QueryAnalysisResourceAllocationTest" --no-daemon
  ```

- [ ] **Step 3: Add admission and owned cancellation only where RED proves a gap**

  Replace request-created ensemble node executors with one Spring-owned bounded executor or a shared lease that remains held until every provider worker exits. Rejection skips optional sampling with a fixed reason. Preserve the triad shared deadline and role isolation.

  In query analysis, create a caller-owned `FutureTask<QueryAnalysisResult>`, call `llmFastExecutor.execute(task)`, cancel that exact task with `cancel(true)` on timeout/interruption, restore caller interrupt, and stop fallback/model expansion after cancellation. If `TimedChatModelCaller`'s current shared executor tests pass, do not edit it; record ID 31 `NO_PATCH_NEEDED` with the exact tests.

- [ ] **Step 4:** Run GREEN and all ensemble/timed-caller/query-analysis tests.
- [ ] **Step 5:** Record IDs 21, 31, 36, and 37 independently.

---

### Task 6: Characterize manager-owned Ollama lifecycle before any edit

**Files:**

- Read/modify only on a new failing contract: `main/java/com/example/lms/config/LocalLlmProcessManager.java`.
- Modify only for a missing contract: `src/test/java/com/example/lms/config/LocalLlmProcessManagerTest.java`.

**Interfaces:**

- Consumes: exact `OwnedProcess` captured from `ProcessBuilder.start()` and fake process-tree operations.
- Produces: dispositions for shutdown gate release, foreign-process non-ownership, and honest detached cleanup.

- [ ] **Step 1: Run exact existing characterization cases**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.config.LocalLlmProcessManagerTest.stopTerminatesOnlyTheManagerOwnedLaunchExactlyOnce" --tests "com.example.lms.config.LocalLlmProcessManagerTest.stopNeverTerminatesAReusedHealthyListenerWithAnObservationalPid" --tests "com.example.lms.config.LocalLlmProcessManagerTest.interruptedStopRestoresFlagRetainsExactCapabilityAndStillRunsCallback" --tests "com.example.lms.config.LocalLlmProcessManagerTest.shellBackedExactGraphReportsTreeScopeIncompleteWithoutOverclaiming" --no-daemon
  ```

- [ ] **Step 2: Decide from current behavior**

  If all four are mutation-sensitive and GREEN, record IDs 33-35 `NO_PATCH_NEEDED`; the current source already owns exact capabilities, releases/retains state truthfully, refuses PID authority, and reports incomplete tree scope. If one fails, freeze a new source preflight for that exact method only.

- [ ] **Step 3: Apply only the failing lifecycle repair**

  Preserve the existing `AtomicReference<OwnedProcess>`, bounded graceful/forced budgets, exact-handle union, interrupt restoration, and no PID/port lookup authority. Add no live-process test and do not touch current Ollama listeners.

- [ ] **Step 4:** Run the full `LocalLlmProcessManagerTest` GREEN and inspect all termination call sites.
- [ ] **Step 5:** Record IDs 33, 34, and 35 with no overclaim about detached unobserved descendants.

---

### Task 7: Bound thumbnail and brain-state background completion with propagated context

**Files:**

- Modify: `main/java/com/example/lms/uaw/thumbnail/UawThumbnailOrchestrator.java` only on RED.
- Modify: `main/java/com/example/lms/uaw/thumbnail/UawThumbnailRunStateStore.java` only on RED.
- Create/modify: `src/test/java/com/example/lms/uaw/thumbnail/UawThumbnailOrchestratorAdmissionTest.java`.
- Modify: `main/java/com/example/lms/service/rag/graph/BrainStateChatWorkflowAspect.java`.
- Modify: `src/test/java/com/example/lms/service/rag/graph/BrainStateChatWorkflowAspectTest.java`.

**Interfaces:**

- Consumes: scheduled thumbnail tick, final memory decision, Trace/MDC/Guard context, Spring-owned executor.
- Produces: one-at-a-time thumbnail lease and context-propagated bounded brain capture with completion state.

- [ ] **Step 1: Write overlapping-tick and context/completion RED tests**

  ```java
  @Test
  void overlappingThumbnailTicksAdmitOneGenerationAndRecordTerminalState() {
      blockFirstGeneration();
      invokeTwoTicks();
      assertThat(thumbnailService.calls()).isEqualTo(1);
      releaseFirst();
      assertThat(runState.lastOutcome()).isEqualTo("succeeded");
  }

  @Test
  void brainCapturePropagatesTraceAndGuardContextAndHasBoundedTerminalOutcome() {
      installSafeContext();
      aspect.captureConversationTurn(joinPoint());
      assertThat(capturedContextHash()).isEqualTo(expectedHash());
      assertThat(awaitOutcome()).isIn("succeeded", "timeout", "rejected", "failed");
  }
  ```

- [ ] **Step 2:** Run the two focused test classes and observe RED only for absent admission/context/completion behavior.
- [ ] **Step 3:** Use an atomic thumbnail lease released in `finally`; reuse `UawThumbnailRunStateStore` for fixed terminal status. Replace brain-state `CompletableFuture.runAsync` common-pool use with a bounded Spring-owned executor and `ContextPropagation.wrapRunnable`; record a count/hash-only terminal outcome. Cancellation/memory-decision denial skips submission.
- [ ] **Step 4:** Run GREEN plus `UawThumbnailServiceGraphRagEventTest`, `BrainStateServiceTest`, and graph-RAG bridge tests.
- [ ] **Step 5:** Record IDs 38 and 39 separately.

---

### Task 8: Verify and close W3

**Files:** All W3 targets and ledger rows 1-4, 9, 10, 18, 21, 23, 29, 31, 33-39.

**Interfaces:**

- Consumes: seven focused packets.
- Produces: executor/lifecycle regression and truthfulness proof.

- [ ] **Step 1:** Run all named W3 test classes sequentially; record XML counts and peak bounded executor metrics from fixtures.
- [ ] **Step 2:** Run `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes -x test` with isolated Desktop build/cache paths.
- [ ] **Step 3:** Run exact-target `git diff --check`, postimage hashes, count-only secret scan, and thread/executor call-site inspection (`newCachedThreadPool`, `newFixedThreadPool`, common-pool `runAsync/supplyAsync`).
- [ ] **Step 4:** Update every W3 ledger row and release leases. Do not run parallel boot smokes, touch unrelated processes, stage, or commit.
