# Demo-1 W4 RAG, Fusion, CFVM, and Vector Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close IDs 8, 20, 22, 24-28, 53, 54, 60, 61, 76, and 77 with valid DLQ defaults, exact top-K semantics, domain-preserving fusion, validated CFVM state, bounded federated operations, and identity-safe plan/rerank behavior.

**Architecture:** Keep the canonical RAG owner in `UnifiedRagOrchestrator`, the active HYPERNOVA/CVaR owner in `com.nova.protocol.fusion`, the active CFVM owner in `com.example.lms.cfvm`, and the canonical DPP owner in `com.example.lms.service.rag.rerank`. Add no shadow implementation. Any algorithm-body edit must first use `demo1-subsystem-patch-directive`; cross-subsystem completion must use `demo1-cross-subsystem-guard` only if the final patch actually crosses two or more S01-S08 boundaries.

**Tech Stack:** Java 17, Spring Boot configuration, LangChain4j 1.0.1 interfaces, JUnit 5, AssertJ, Gradle Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-25-demo1-100-issue-safe-patch-design.md`

## Global Constraints

- Preserve calibrated `[0,1]` scores as calibrated values; do not normalize them with a raw-score maximum.
- Preserve candidate identity through every reorder. Index position is never candidate identity.
- Use one monotonic deadline for each federated operation. Per-store timeout is capped by remaining time.
- A partial federated write reports each store outcome without fabricating all-store success.
- Reject non-finite snapshot values before mutating live CFVM state.
- Treat `topK < 0` as invalid and `topK == 0` as an empty result.
- Characterize already-correct behavior before editing. An existing green acceptance contract closes the ID as `NO_PATCH_NEEDED`.
- Each changed production cohort requires its own stable three-way source-edit preflight and immediate preimage recheck.

## Coverage

| IDs | Task | Active owner |
|---|---|---|
| 8 | 1 | `VectorQuarantineDlqService`, `VectorDlqRedriveScheduler` |
| 22, 53, 54 | 2 | `UnifiedRagOrchestrator` and public RAG admission |
| 24, 25 | 3 | `CvarAggregator`, `NovaNextFusionService` |
| 26, 27, 28 | 4 | `CfvmFailureRecorder`, `RawMatrixBuffer` |
| 20, 60, 61 | 5 | `FederatedEmbeddingStore` |
| 76, 77 | 6 | canonical DPP bridge and `PlanHintApplier` |

---

### Task 0: Freeze W4 source ownership and numeric domains

**Files:** All task targets, adjacent focused tests, active Gradle source-set evidence, and the W4 rows in the terminal ledger.

**Interfaces:**

- Consumes: active source-set map, current configuration defaults, target hashes, score-domain declarations, and request-budget contract.
- Produces: six separately gated cohorts and a frozen table of input/output domains.

- [ ] **Step 1:** Refresh branch, HEAD, source sets, Java 17, index lock, worktrees, PatchDrop inventory, source-edit leases, and target hashes.
- [ ] **Step 2:** Record score domains before edits: raw retrieval score, calibrated confidence, CVaR input/output, DPP relevance, and guard-band bounds.
- [ ] **Step 3:** Run the existing focused baselines listed in Tasks 1-6. A baseline failure is recorded against its exact test and does not authorize broad cleanup.
- [ ] **Step 4:** For every algorithm-body cohort that needs a patch, load `demo1-subsystem-patch-directive`, freeze a redacted EvidenceSnapshot, run exactly the required three-way source preflight, and proceed only on stable `APPLY`.

---

### Task 1: Make vector-DLQ enablement and redrive health coherent

**Files:**

- Modify if RED: `main/java/com/example/lms/service/vector/VectorQuarantineDlqService.java`.
- Modify if RED: `main/java/com/example/lms/scheduler/VectorDlqRedriveScheduler.java`.
- Modify: `src/test/java/com/example/lms/service/vector/VectorQuarantineDlqServiceTest.java`.
- Create if no equivalent exists: `src/test/java/com/example/lms/scheduler/VectorDlqRedriveSchedulerConfigurationTest.java`.

**Interfaces:**

- Consumes: `vector.dlq.enabled`, `vector.dlq.redrive.enabled`, optional backend health, and DLQ service presence.
- Produces: either an operational redrive path or an explicit redacted `disabledReason` that health output can distinguish.

- [ ] **Step 1: Add an invalid-combination characterization**

  ```java
  @Test
  void enabledDlqCannotAppearHealthyWhenRedriveIsDisabled() {
      context.withPropertyValues(
              "vector.dlq.enabled=true",
              "vector.dlq.redrive.enabled=false")
          .run(ctx -> assertThat(dlqHealth(ctx))
              .containsEntry("enabled", true)
              .containsEntry("redriveEnabled", false)
              .containsEntry("disabledReason", "redrive_disabled"));
  }
  ```

  If an existing stricter property makes the combination impossible, assert that startup fails with an allowlisted reason instead and close ID 8 as `NO_PATCH_NEEDED`.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.vector.VectorQuarantineDlqServiceTest" --tests "com.example.lms.scheduler.VectorDlqRedriveSchedulerConfigurationTest" --no-daemon
  ```

- [ ] **Step 3: Implement the smallest coherent contract**

  Prefer an explicit health projection over silently enabling an operator-disabled scheduler. Expose only booleans and a fixed reason code; do not expose paths, payloads, or backend identifiers. Do not change the default `vector.dlq.enabled=false`.

- [ ] **Step 4:** Re-run focused tests and record ID 8 with the effective property combination and reason code.

---

### Task 2: Enforce exact top-K admission, zero semantics, and final trimming

**Files:**

- Modify: `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`.
- Modify: `src/test/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestratorPlanHintsTest.java`.
- Modify: `src/test/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestratorDependencyHonestyTest.java`.
- Modify only if it is the active public admission owner: the existing controller/request validator reached from the focused call-path trace.

**Interfaces:**

- Consumes: admitted web/vector/final top-K, seed-only contents, repaired/memory-augmented candidates.
- Produces: validation failure for negative values, exactly zero candidates for zero, and at most final top-K documents after all augmentation.

- [ ] **Step 1: Add RED contracts for IDs 22, 53, and 54**

  ```java
  @Test
  void seedOnlyTopKZeroReturnsNoCandidates() {
      Request req = seededRequest(0, List.of(content("must-not-escape")));
      assertThat(run(req).documents()).isEmpty();
  }

  @Test
  void finalTrimRunsAfterRepairAndMemoryAugmentation() {
      Request req = requestWithFinalTopK(2);
      seedThreeDistinctAugmentedCandidates();
      assertThat(run(req).documents()).hasSize(2);
  }

  @Test
  void negativeWebTopKIsRejectedBeforeProviderExecution() {
      assertThatThrownBy(() -> run(requestWithWebTopK(-1)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(webProviderAttempts()).isZero();
  }
  ```

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.rag.orchestrator.UnifiedRagOrchestratorPlanHintsTest" --tests "com.example.lms.service.rag.orchestrator.UnifiedRagOrchestratorDependencyHonestyTest" --no-daemon
  ```

- [ ] **Step 3: Correct zero handling at the active conversion seam**

  Replace the `Math.max(1, topK)` behavior in `toDocsFromContents` with explicit validation and zero return. Do not use `Integer.MAX_VALUE` as a zero sentinel.

- [ ] **Step 4: Apply one final stable trim**

  Add or reuse one helper that validates `topK` and returns a copy of the first `topK` elements after repair, memory augmentation, fusion, and rerank are complete. Preserve deterministic order and do not mutate an immutable list.

- [ ] **Step 5:** Re-run the focused tests and the public admission test discovered in Step 1. Record provider-attempt count `0` for the negative case.

---

### Task 3: Preserve fusion score domains and finite guard bands

**Files:**

- Modify if RED: `main/java/com/nova/protocol/fusion/CvarAggregator.java`.
- Modify if RED: `main/java/com/nova/protocol/fusion/NovaNextFusionService.java`.
- Modify: `src/test/java/com/nova/protocol/fusion/NovaCvarAggregatorTest.java`.
- Modify: `src/test/java/com/nova/protocol/fusion/NovaNextFusionServiceTest.java`.
- Modify: `src/test/java/com/nova/protocol/fusion/NovaNextFusionBoundedProbeTest.java`.

**Interfaces:**

- Consumes: declared-domain finite scores, calibrated confidence, tail fraction/quantile, and maximum adjustment.
- Produces: finite ordered scores in the declared domain and a non-inverting finite guard band.

- [ ] **Step 1: Add all-negative and mixed-domain RED cases**

  ```java
  @Test
  void allNegativeRawScoresRemainOrderedAndFinite() {
      List<ScoredResult> out = fuse(rawScores(-0.9, -0.4, -0.1));
      assertThat(out).extracting(ScoredResult::getId)
          .containsExactly("least-negative", "middle", "most-negative");
      assertThat(out).allSatisfy(r -> {
          assertThat(r.getScore()).isFinite();
          assertThat(r.getGuardBand()).isFinite().isGreaterThanOrEqualTo(0.0);
      });
  }

  @Test
  void calibratedScoresAreNotRenormalizedByUnrelatedRawMaximum() {
      assertThat(fuse(mixedDomainFixture()).get(0).getCalibratedConfidence())
          .isEqualTo(0.8);
  }
  ```

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.nova.protocol.fusion.NovaCvarAggregatorTest" --tests "com.nova.protocol.fusion.NovaNextFusionServiceTest" --tests "com.nova.protocol.fusion.NovaNextFusionBoundedProbeTest" --no-daemon
  ```

- [ ] **Step 3: Patch only the proven domain mismatch**

  Keep raw scores raw or transform them with a declared monotonic mapping. Clamp only fields whose contract is `[0,1]`. Compute a guard interval with ordered lower/upper bounds even when the base score is negative; never force a raw negative lower bound to zero merely because calibrated confidence uses `[0,1]`.

- [ ] **Step 4:** Re-run focused tests twice with candidate order reversed. Require identical identity ordering and finite numeric traces.

---

### Task 4: Separate cancellation from downgrade and validate CFVM restoration

**Files:**

- Modify if RED: `main/java/com/example/lms/cfvm/CfvmFailureRecorder.java`.
- Modify if RED: `main/java/com/example/lms/cfvm/RawMatrixBuffer.java`.
- Modify: `src/test/java/com/example/lms/cfvm/CfvmFailureRecorderTest.java`.
- Modify: `src/test/java/com/example/lms/cfvm/CfvmSnapshotRoundTripTest.java`.
- Modify: `src/test/java/com/example/lms/cfvm/RawMatrixBufferTest.java`.

**Interfaces:**

- Consumes: classified failure/cancellation outcome, snapshot weights, temperature, and the current buffer domain.
- Produces: no failure-memory mutation for non-cancel downgrade, atomic validated restore, and immediately recomputed effective weights after temperature change.

- [ ] **Step 1: Add one test per acceptance condition**

  ```java
  @Test
  void nonCancelDowngradeDoesNotRecordCancellationPattern() {
      recorder.record("rag", "quality_downgrade", "orchestration", "s1", downgradeTrace());
      assertThat(failureMemoryWrites()).isZero();
      assertThat(rawBufferWrites()).isZero();
  }

  @Test
  void restoreRejectsNonFiniteSnapshotAtomically() {
      double[] before = buffer.snapshotWeights();
      assertThatThrownBy(() -> buffer.restoreFromSnapshot(new double[] {0.2, Double.NaN}, 1.0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(buffer.snapshotWeights()).containsExactly(before);
  }

  @Test
  void temperatureChangeImmediatelyChangesEffectiveWeights() {
      double[] before = buffer.effectiveWeights();
      buffer.setBoltzmannTemp(0.25);
      assertThat(buffer.effectiveWeights()).isNotEqualTo(before);
  }
  ```

  Use the actual existing snapshot/effective-weight accessors. If the third test is already green, record ID 28 `NO_PATCH_NEEDED` and do not rewrite the cache.

- [ ] **Step 2: Run focused RED/characterization**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.cfvm.CfvmFailureRecorderTest" --tests "com.example.lms.cfvm.CfvmSnapshotRoundTripTest" --tests "com.example.lms.cfvm.RawMatrixBufferTest" --no-daemon
  ```

- [ ] **Step 3: Implement atomic validation and explicit outcome classification**

  Validate array length, every finite weight, finite positive temperature, and documented domain before assigning any field. Pass a typed or fixed-enum outcome into the recorder rather than treating every downgrade string as cancellation. Preserve current public constructors unless a focused compile failure proves a migration is needed.

- [ ] **Step 4:** Re-run tests; record before/post hashes of effective weights and zero mutation counts for the downgrade case.

---

### Task 5: Bound federated search and write with one deadline and per-store outcomes

**Files:**

- Modify: `main/java/com/example/lms/vector/FederatedEmbeddingStore.java`.
- Modify: `src/test/java/com/example/lms/vector/FederatedEmbeddingStoreTest.java`.
- Modify: `src/test/java/com/example/lms/vector/FederatedEmbeddingStoreWriteContractTest.java`.
- Create only if an existing executor bean cannot be reused: `main/java/com/example/lms/vector/FederatedStoreOperationExecutor.java`.

**Interfaces:**

- Consumes: store list, requested max results, one operation deadline, and bounded shared admission.
- Produces: merged partial search results plus truthful unfinished-store reasons, and a per-store write result.

- [ ] **Step 1: Add deterministic latch-based RED cases**

  ```java
  @Test
  void searchUsesOneTotalDeadlineAcrossAllStores() {
      storeA.blocksIgnoringInterrupt();
      storeB.blocksIgnoringInterrupt();
      assertThat(measureMillis(() -> federated.search(requestWithTimeout(100))))
          .isLessThan(220);
  }

  @Test
  void stuckStoresCannotConsumeEveryFutureSearchSlot() {
      occupyConfiguredStoreSlots();
      assertThat(federated.search(fastRequest()).matches()).isNotEmpty();
      assertThat(trace("federated.search.unfinishedCount")).isPositive();
  }

  @Test
  void writeDeadlineReportsPerStorePartialOutcome() {
      fastStore.succeeds();
      stuckStore.blocksIgnoringInterrupt();
      FederatedWriteResult result = federated.write(writeRequestWithTimeout(100));
      assertThat(result.outcomes()).containsEntry("fast", SUCCEEDED);
      assertThat(result.outcomes()).containsEntry("stuck", DEADLINE_EXCEEDED);
  }
  ```

  Do not use `Thread.sleep`; release every latch and close test executors in `finally`/`@AfterEach`.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.vector.FederatedEmbeddingStoreTest" --tests "com.example.lms.vector.FederatedEmbeddingStoreWriteContractTest" --no-daemon
  ```

- [ ] **Step 3: Implement one monotonic operation deadline**

  Submit only while bounded admission is available. Collect completed futures until the one deadline; cancel unfinished handles as a request, record `worker_unfinished` when termination is unobserved, and return completed results. Do not wait `timeout` once per future.

- [ ] **Step 4: Add a compatibility-safe write result**

  Keep LangChain4j's required `void addAll(...)` methods. Route them through an internal result-returning method and preserve the current all-store-failed exception. Expose or trace only safe store IDs, counts, and fixed outcome codes.

- [ ] **Step 5:** Re-run focused tests in a fresh Gradle process and assert executor/admission counts return to baseline after releasing blockers.

---

### Task 6: Preserve candidate identity through DPP and apply or reject active plan fields

**Files:**

- Modify if RED: `main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java`.
- Modify if RED: `main/java/com/nova/protocol/fusion/NovaNextFusionService.java`.
- Modify: `src/test/java/com/example/lms/service/rag/rerank/DppDiversityRerankerTest.java`.
- Modify: `src/test/java/com/nova/protocol/fusion/NovaNextFusionServiceTest.java`.
- Modify if RED: `main/java/com/example/lms/plan/PlanHintApplier.java`.
- Modify: `src/test/java/com/example/lms/plan/PlanHintApplierTest.java`.
- Modify: `src/test/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestratorPlanHintsTest.java`.

**Interfaces:**

- Consumes: candidate ID/content/score tuple, RRF/DPP reorder, and plan fields `topk`, `budget_ms`, and guards.
- Produces: the same identity attached to each reordered score and either an applied bounded plan value or an explicit rejection reason.

- [ ] **Step 1: Add an identity-sentinel contract**

  ```java
  @Test
  void dppRrfBridgeNeverReattachesScoresByListIndex() {
      List<ScoredResult> out = rerank(candidates(
          candidate("A", "alpha", 0.2),
          candidate("B", "beta", 0.9),
          candidate("C", "gamma", 0.5)));
      assertThat(out).allSatisfy(result ->
          assertThat(result.getContent()).isEqualTo(originalContent(result.getId())));
  }
  ```

- [ ] **Step 2: Add plan-field acceptance/rejection contracts**

  Parse representative nested and legacy forms for `topk` and `budget_ms`; assert clamped positive values reach the active orchestrator context. Negative, nonnumeric, overflow, or guard-violating fields must produce a fixed rejection reason and leave defaults intact.

- [ ] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.rag.rerank.DppDiversityRerankerTest" --tests "com.nova.protocol.fusion.NovaNextFusionServiceTest" --tests "com.example.lms.plan.PlanHintApplierTest" --tests "com.example.lms.service.rag.orchestrator.UnifiedRagOrchestratorPlanHintsTest" --no-daemon
  ```

- [ ] **Step 4: Patch only the active bridge/parser seam**

  Carry a stable ID-to-candidate map through reorder. Never zip reordered scores with the original list by position. For plan fields, use existing conversion helpers and record allowlisted applied/rejected keys; do not invent a second plan model.

- [ ] **Step 5:** Re-run focused tests with input order reversed. If the suspected DPP or plan path is inactive, prove that with call-path evidence and close the corresponding ID as `NO_PATCH_NEEDED`, not with speculative code.

---

### Task 7: Verify and close W4

**Files:** W4 production/test diffs and the terminal ledger.

- [ ] **Step 1:** Run all W4 focused tests together with isolated Gradle state.

  ```powershell
  $env:AWX_SPLIT_BUILD_OUTPUTS = '1'
  $env:AWX_BUILD_HOST_ID = 'desktop-w4'
  $env:GRADLE_USER_HOME = (Join-Path $env:TEMP 'awx-gradle-user-w4')
  .\gradlew.bat test --tests "com.example.lms.service.vector.VectorQuarantineDlqServiceTest" --tests "com.example.lms.scheduler.VectorDlqRedriveSchedulerConfigurationTest" --tests "com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator*Test" --tests "com.nova.protocol.fusion.Nova*Test" --tests "com.example.lms.cfvm.CfvmFailureRecorderTest" --tests "com.example.lms.cfvm.CfvmSnapshotRoundTripTest" --tests "com.example.lms.cfvm.RawMatrixBufferTest" --tests "com.example.lms.vector.FederatedEmbeddingStore*Test" --tests "com.example.lms.service.rag.rerank.DppDiversityRerankerTest" --tests "com.example.lms.plan.PlanHintApplierTest" --no-daemon --project-cache-dir (Join-Path $env:TEMP 'awx-project-cache-w4')
  ```

- [ ] **Step 2:** Run `compileJava`, source-set hygiene, LangChain4j version purity, and targeted secret scan for changed files.
- [ ] **Step 3:** Inspect the diff for duplicate owners, positional identity joins, raw score-domain changes, per-future timeout loops, global executor creation, and unrelated formatting.
- [ ] **Step 4:** Close all 14 W4 rows with `PATCHED`, `NO_PATCH_NEEDED`, or a lane-local `HOLD` containing exact evidence and one verification action. Leave no row merely `OPEN`.
- [ ] **Step 5:** Record a no-commit checkpoint. Do not commit, push, deploy, or mutate external vector/provider systems without separate authority.
