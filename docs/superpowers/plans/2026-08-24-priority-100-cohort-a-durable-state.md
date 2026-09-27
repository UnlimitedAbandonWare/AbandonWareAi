# Priority-100 Cohort A Durable State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Repository policy keeps every source write, integration decision, verification claim, and final judgment in the parent Codex session.

**Goal:** Make pending-memory promotion, scheduled document indexing, and image-job dispatch advertise success only after the side effect they own is durably or atomically acknowledged.

**Architecture:** `VectorStoreService.flush()` returns an internal, non-logged `VectorFlushOutcome` for the exact atomically swapped snapshot: complete, empty, backoff, or store failure. `PendingMemorySoakScheduler` retains every successfully enqueued leased row as `PENDING` until that batch receives a durable outcome; `IndexingScheduler` reinforces and advances its cursor only after the same durable outcome. `ImageJobRepository` uses a conditional database update so only one node can change a selected job from `PENDING` to `IN_PROGRESS` before the provider call.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, LangChain4j `1.0.1`, JUnit 5, Mockito, AssertJ, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`

## Global Constraints

- Active backend source is root `main/java` and `main/resources`; active tests are under `src/test/java`.
- Keep every `dev.langchain4j` dependency exactly on `1.0.1`; add no production dependency.
- Preserve all unrelated user hunks. `VectorStoreService.java` and `TranslationMemory.java` were already modified during intake; `ImageJobRepository.java` was untracked.
- Recompute target preimage hashes immediately before each patch and stop only the overlapping lane if a preimage changes.
- Before application-source mutation, freeze one redacted EvidenceSnapshot and execute exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`; require stable `APPLY` in A-B and B-A packet order.
- Acquire the existing Desktop source-edit lease before writing application source and release it in `finally` after verification or abort.
- Keep `TranslationMemory.MemoryStatus` ordinal order unchanged. This plan adds no enum value and requires no database migration.
- `VectorFlushOutcome` carries only a boolean, counts, and a fixed reason code. Do not add raw vector IDs or exception messages to it, logs, traces, HTTP, or persistent diagnostics.
- Diagnostics contain only fixed reason codes, counts, booleans, durations, and hashes; never raw content, prompts, session IDs, vector IDs, provider responses, credentials, or full exception bodies.
- Do not stage, commit, push, merge, deploy, mutate a live database, clean worktrees/build output, or call an external provider.
- `FIXED` requires behavioral proof. Existing source-order string assertions are supporting evidence only.

---

## File Structure

### Production files

- Modify `main/java/com/example/lms/service/VectorStoreService.java`: own attempt-scoped `VectorFlushOutcome` at the existing `flush()` boundary; do not modify the already-dirty enqueue/poison/chunking hunk.
- Modify `main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java`: retain every claimed-and-enqueued row as `PENDING` until the exact flush snapshot reports durable completion.
- Modify `main/java/com/example/lms/scheduler/IndexingScheduler.java`: gate reinforcement and cursor advancement on the same flush outcome.
- Modify `main/java/com/example/lms/plugin/image/jobs/ImageJobRepository.java`: add the conditional `PENDING` to `IN_PROGRESS` update.
- Modify `main/java/com/example/lms/plugin/image/jobs/ImageJobService.java`: require a successful conditional claim before manifest or provider work.

### Test files

- Create `src/test/java/com/example/lms/service/VectorStoreServiceFlushOutcomeTest.java`: complete, empty, swallowed failure, backoff, partial-success count, and redaction behavior.
- Create `src/test/java/com/example/lms/scheduler/PendingMemorySoakSchedulerBehaviorTest.java`: failed/rejected/partial vector outcomes remain retryable; complete persistence promotes once.
- Create `src/test/java/com/example/lms/scheduler/IndexingSchedulerBehaviorTest.java`: failed or partial persistence causes neither reinforcement nor cursor advance; success causes both once.
- Create `src/test/java/com/example/lms/plugin/image/jobs/ImageJobRepositoryClaimTest.java`: first conditional claim succeeds and the second returns zero.
- Create `src/test/java/com/example/lms/plugin/image/jobs/ImageJobServiceClaimTest.java`: a lost claim invokes neither manifest nor provider.
- Modify `src/test/java/com/example/lms/service/OperationalQualitySourceContractTest.java`: remove or narrow assertions that treat normal `flush()` return as proof of persistence.

### Existing supporting tests to retain

- `src/test/java/com/example/lms/scheduler/SchedulerRedactionContractTest.java`
- `src/test/java/com/example/lms/service/VectorStoreServiceLmsDiagnosticsRedactionTest.java`
- `src/test/java/com/example/lms/plugin/image/jobs/ImageJobServiceDebugDiagnosticsTest.java`
- `src/test/java/com/example/lms/plugin/image/jobs/ImageJobServicePathRedactionTest.java`
- `src/test/java/com/example/lms/plugin/image/ImageJobServiceSourceContractTest.java`

---

### Task 0: Freeze the source-edit decision and ownership boundary

**Files:**

- Read: `AGENTS.md`
- Read: `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`
- Read: this plan
- Read: `__patch_drop__/janitor_inventory.ps1`
- Read: `__patch_drop__/source_edit_session.ps1`
- Read: every production and test target listed under File Structure

**Interfaces:**

- Consumes: written-spec approval `문서 명세 승인`, current branch/HEAD, exact target status, source-set evidence, PatchDrop inventory, lease status, and target SHA-256 values.
- Produces: one at-most-20-row redacted EvidenceSnapshot, stable three-way `APPLY`, verified non-overlapping target preimages, and one active `desktop` source-edit lease.

- [ ] **Step 1: Refresh the repository boundary**

  Run from the workspace root:

  ```powershell
  java -version
  .\gradlew.bat --version
  git branch --show-current
  git rev-parse HEAD
  git worktree list --porcelain
  git status --short -- `
    main/java/com/example/lms/service/VectorStoreService.java `
    main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java `
    main/java/com/example/lms/scheduler/IndexingScheduler.java `
    main/java/com/example/lms/plugin/image/jobs/ImageJobRepository.java `
    main/java/com/example/lms/plugin/image/jobs/ImageJobService.java `
    src/test/java/com/example/lms/service/VectorStoreServiceFlushOutcomeTest.java `
    src/test/java/com/example/lms/scheduler/PendingMemorySoakSchedulerBehaviorTest.java `
    src/test/java/com/example/lms/scheduler/IndexingSchedulerBehaviorTest.java `
    src/test/java/com/example/lms/plugin/image/jobs/ImageJobRepositoryClaimTest.java `
    src/test/java/com/example/lms/plugin/image/jobs/ImageJobServiceClaimTest.java
  Test-Path -LiteralPath .git\index.lock
  & powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
  & powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action status
  ```

  Expected: Java 17, the current Desktop branch/HEAD, no index lock, no active overlapping source lease, and no active top-level PatchDrop patch for these targets. A dirty or untracked target is not automatically safe or unsafe; inspect its exact diff/provenance.

- [ ] **Step 2: Prove active ownership and record target preimages**

  Run the repository source-set checks and hash exact targets:

  ```powershell
  $env:AWX_SPLIT_BUILD_OUTPUTS = '1'
  $env:AWX_BUILD_HOST_ID = 'desktop'
  .\gradlew.bat checkSourceSetHygiene checkLangchain4jVersionPurity --no-daemon

  $targets = @(
    'main/java/com/example/lms/service/VectorStoreService.java',
    'main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java',
    'main/java/com/example/lms/scheduler/IndexingScheduler.java',
    'main/java/com/example/lms/plugin/image/jobs/ImageJobRepository.java',
    'main/java/com/example/lms/plugin/image/jobs/ImageJobService.java'
  )
  $targets | ForEach-Object {
    [pscustomobject]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
  }
  ```

  Expected: active root source ownership and exact hashes. Do not print Git top-level paths resolved to UNC or raw environment values.

- [ ] **Step 3: Freeze and adjudicate exactly the repository-required three-way packets**

  Build one redacted EvidenceSnapshot with at most 20 rows containing only evidence IDs, paths, SHA-256 values, Git status, source-set owner, and boolean gate results. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY` through `demo1-source-edit-three-way-preflight`. Evaluate the neutral packet in A-B and B-A order.

  Expected: `APPLY` in both orders with the same decisive evidence IDs. Any order-dependent result is `HOLD`. Do not add a fourth reviewer or use majority voting.

- [ ] **Step 4: Acquire the Desktop source-edit lease**

  ```powershell
  & powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
    -Action begin -Role desktop -Root . `
    -Topic priority-100-cohort-a-durable-state `
    -OwnerId codex-root-priority-100-a -TtlMinutes 180
  ```

  Expected: exit code `0` and one source-edit lock. If `ImageJobRepository.java` remains an unowned untracked file, set only the image-claim task to `HOLD`; continue vector/scheduler work when its own targets pass.

---

### Task 1: Return an attempt-scoped vector flush outcome

**Files:**

- Modify: `main/java/com/example/lms/service/VectorStoreService.java:725-974`
- Create: `src/test/java/com/example/lms/service/VectorStoreServiceFlushOutcomeTest.java`
- Test: `src/test/java/com/example/lms/service/VectorStoreServiceLmsDiagnosticsRedactionTest.java`

**Interfaces:**

- Consumes: the existing atomic `queueRef` swap, snapshot size, `okIds`, current-queue size, and backoff state.
- Produces `VectorFlushOutcome flush()` with exact fields `boolean durable`, `int succeededCount`, `int pendingCount`, and fixed `String reasonCode`. Existing Java call statements may continue ignoring the returned value.

- [ ] **Step 1: Write RED tests for complete, empty, failed, backoff, and partial outcomes**

  Create `VectorStoreServiceFlushOutcomeTest` using the real service's existing two-argument constructor, a deterministic fake `EmbeddingModel`, and a deterministic fake `EmbeddingStore<TextSegment>`. Set `batchSize=1000`, `shadowWriteEnabled=false`, and backoff fields with `ReflectionTestUtils` as existing tests do. The external embedding/store boundary may be faked; assertions target the real buffer/flush state machine.

  The tests must contain these assertions:

  ```java
  service.enqueue("sid", "alpha", Map.of());
  VectorStoreService.VectorFlushOutcome outcome = service.flush();

  assertThat(outcome.durable()).isTrue();
  assertThat(outcome.succeededCount()).isEqualTo(1);
  assertThat(outcome.pendingCount()).isZero();
  assertThat(outcome.reasonCode()).isEqualTo("complete");
  ```

  A store that throws from `addAll(ids, embeddings, segments)` must produce:

  ```java
  assertThat(outcome.durable()).isFalse();
  assertThat(outcome.succeededCount()).isZero();
  assertThat(outcome.pendingCount()).isEqualTo(1);
  assertThat(outcome.reasonCode()).isEqualTo("store_failure");
  assertThat(service.pendingSize()).isEqualTo(1);
  ```

  Add an empty-buffer case expecting `durable=true`, zero counts, and `empty`. Add a backoff case expecting `durable=false`, the current pending count, and `backoff`. Add a two-group fixture where the first group succeeds and the second throws; expect `durable=false`, `succeededCount=1`, `pendingCount=1`, and `store_failure`. Assert that `outcome.toString()` contains no raw text, session ID, vector ID, or exception message fixture.

- [ ] **Step 2: Run the outcome test and verify RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.VectorStoreServiceFlushOutcomeTest" --no-daemon
  ```

  Expected: compilation failure because `VectorFlushOutcome` and non-void `flush()` do not exist. A fixture or fake-store setup failure is not acceptable RED evidence.

- [ ] **Step 3: Add the immutable, non-identifying outcome type**

  Add this nested public record beside `VectorBufferStats`:

  ```java
  public record VectorFlushOutcome(
          boolean durable,
          int succeededCount,
          int pendingCount,
          String reasonCode) {
      public VectorFlushOutcome {
          succeededCount = Math.max(0, succeededCount);
          pendingCount = Math.max(0, pendingCount);
          reasonCode = reasonCode == null || reasonCode.isBlank() ? "unknown" : reasonCode;
      }
  }
  ```

- [ ] **Step 4: Return an outcome on every flush exit**

  Change only `public synchronized void flush()` to `public synchronized VectorFlushOutcome flush()`. Do not modify `enqueue(...)` or its dirty poison/chunking hunk. Implement the exits as follows:

  ```java
  if (now < backoffUntilEpochMs) {
      return new VectorFlushOutcome(false, 0, pendingSize(), "backoff");
  }

  ConcurrentHashMap<String, BufferEntry> snapshotMap =
          queueRef.getAndSet(new ConcurrentHashMap<>());
  if (snapshotMap.isEmpty()) {
      return new VectorFlushOutcome(true, 0, pendingSize(), "empty");
  }
  ```

  Keep the existing `okIds` collection. On complete success return `new VectorFlushOutcome(true, okIds.size(), pendingSize(), "complete")`. In the existing catch, restore only IDs not in `okIds`, then return `new VectorFlushOutcome(false, okIds.size(), pendingSize(), "store_failure")`. Do not put `e.toString()` or any raw message in the outcome.

  `triggerFlushIfDue()` may continue returning `void`; tracked scheduler callers use explicit `flush()`.

- [ ] **Step 5: Run focused GREEN and redaction regressions**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.service.VectorStoreServiceFlushOutcomeTest" `
    --tests "com.example.lms.service.VectorStoreServiceLmsDiagnosticsRedactionTest" `
    --tests "com.example.lms.service.VectorStoreServiceGraphDbManualMetadataTest" `
    --no-daemon
  ```

  Expected: all selected tests pass; the outcome contains only a boolean, counts, and a fixed reason code, with no new `VectorStoreService.java` diff inside the pre-existing enqueue hunk.

---

### Task 2: Keep pending memories leased and retryable until vector persistence

**Files:**

- Modify: `main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java:55-195`
- Create: `src/test/java/com/example/lms/scheduler/PendingMemorySoakSchedulerBehaviorTest.java`
- Test: `src/test/java/com/example/lms/scheduler/SchedulerRedactionContractTest.java`

**Interfaces:**

- Consumes: the existing successful return from `enqueue(...)`, `VectorFlushOutcome.durable()`, existing PENDING lease fields, and deterministic `(sessionId, content)` vector IDs.
- Produces: a private list of successfully enqueued `TranslationMemory` candidates and the state rule `ACTIVE iff the candidate batch receives durable=true`.

  Define the lease helper exactly as:

  ```java

  private void releasePendingLease(TranslationMemory memory, String reasonCode) {
      memory.setStatus(TranslationMemory.MemoryStatus.PENDING);
      memory.setLockedAt(null);
      memory.setLockedBy(null);
      repo.save(memory);
      TraceStore.put("pendingSoak.retry.reason", reasonCode);
  }
  ```

  `reasonCode` is supplied only from the fixed local set `enqueue_failure`, `flush_backoff`, `flush_failure`, and `flush_partial`.

- [ ] **Step 1: Write state-transition RED tests**

  Construct the scheduler with mocked `TranslationMemoryRepository`, `VectorStoreService`, and `VectorPoisonGuard`; set `batchSize`, `leaseMinutes`, `maxAgeHours`, and `minEvidence` with `ReflectionTestUtils`. Use content `"[W1] supported fact"` and:

  ```java
  new VectorPoisonGuard.IngestDecision(true, content, Map.of(), "", 0.0d)
  ```

  Required cases:

  ```java
  // enqueue rejected or throws
  assertThat(memory.getStatus()).isEqualTo(TranslationMemory.MemoryStatus.PENDING);
  assertThat(memory.getLockedAt()).isNull();
  assertThat(memory.getLockedBy()).isNull();

  // enqueue returns but flush reports backoff/store_failure
  assertThat(memory.getStatus()).isEqualTo(TranslationMemory.MemoryStatus.PENDING);

  // explicit flush reports durable completion (including empty after successful auto-flush)
  assertThat(memory.getStatus()).isEqualTo(TranslationMemory.MemoryStatus.ACTIVE);
  verify(repository, times(1)).save(memory);
  ```

  Add a process-crash characterization: leave a claimed `PENDING` row with an expired lease, run the scheduler again with the same content, and assert the deterministic vector upsert plus durable outcome permits one final transition without a new memory row.

- [ ] **Step 2: Run the scheduler behavior test and verify RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.scheduler.PendingMemorySoakSchedulerBehaviorTest" --no-daemon
  ```

  Expected: the enqueue-failure case observes the current illegal `ACTIVE` state, or compilation fails because `VectorFlushOutcome` is not yet consumed.

- [ ] **Step 3: Queue candidates without advertising ACTIVE**

  During the claimed-row loop, keep validation/quarantine branches unchanged. Replace the pre-enqueue promotion block with:

  ```java
  vectorStoreService.enqueue(tm.getSessionId(), content, meta);
  promotions.add(tm);
  ```

  Do not set `ACTIVE` or clear the lease in this loop. `releasePendingLease` sets status to `PENDING`, clears `lockedAt` and `lockedBy`, saves once, and records only a fixed reason code plus hashed row/session identifiers. On enqueue exception, call it with `enqueue_failure` and do not add that row to `promotions`.

- [ ] **Step 4: Flush once and finalize every candidate from the same attempt outcome**

  After the loop, call `vectorStoreService.flush()` once when `promotions` is non-empty. If the call throws, synthesize the local decision `durable=false` without exposing the exception message.

  ```java
  VectorStoreService.VectorFlushOutcome outcome = vectorStoreService.flush();
  boolean persisted = outcome.durable();
  promotion.setStatus(persisted
          ? TranslationMemory.MemoryStatus.ACTIVE
          : TranslationMemory.MemoryStatus.PENDING);
  promotion.setLockedAt(null);
  promotion.setLockedBy(null);
  repo.save(promotion);
  ```

  A thrown flush, `backoff`, `store_failure`, or partial result keeps every candidate row `PENDING`. `empty` is durable because it means an enqueue-triggered auto-flush already completed before the explicit flush. Do not use aggregate timestamps from `bufferStats()`.

- [ ] **Step 5: Run focused GREEN and existing scheduler contracts**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.scheduler.PendingMemorySoakSchedulerBehaviorTest" `
    --tests "com.example.lms.scheduler.SchedulerRedactionContractTest" `
    --no-daemon
  ```

  Expected: every non-durable vector outcome remains reclaimable and every durable outcome promotes exactly once.

---

### Task 3: Couple indexing reinforcement and cursor advancement to the flush outcome

**Files:**

- Modify: `main/java/com/example/lms/scheduler/IndexingScheduler.java:55-120`
- Create: `src/test/java/com/example/lms/scheduler/IndexingSchedulerBehaviorTest.java`
- Modify: `src/test/java/com/example/lms/service/OperationalQualitySourceContractTest.java:244-276`

**Interfaces:**

- Consumes: the existing enqueue calls, final `VectorFlushOutcome`, and existing `MemoryReinforcementService.reinforceWithSnippet(String, String, String, String, double)`.
- Produces: one batch predicate `flush outcome is durable`; only that predicate permits reinforcement and `lastFetchTime` advancement.

- [ ] **Step 1: Write failure and retry RED tests**

  Use a real `VectorStoreService` with a deterministic fake embedding model and a fake embedding store that throws after input capture. Mock only `DocumentFetcher` and `MemoryReinforcementService`. Return one stable document on consecutive calls so the real flush catches the store failure and returns `durable=false`.

  Assert:

  ```java
  scheduler.scheduleIndexing();
  verify(memoryService, never()).reinforceWithSnippet(anyString(), any(), anyString(), anyString(), anyDouble());

  scheduler.scheduleIndexing();
  ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
  verify(fetcher, times(2)).fetchNewDocumentsSince(from.capture());
  assertThat(from.getAllValues().get(1)).isEqualTo(from.getAllValues().get(0));
  ```

  Add a partial-success two-group case; because the outcome is not durable, assert zero reinforcement and no cursor advance. Add a complete-success case asserting each segment is reinforced once and the second fetch uses a later cursor.

- [ ] **Step 2: Run the indexing test and verify RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.scheduler.IndexingSchedulerBehaviorTest" --no-daemon
  ```

  Expected: current code reinforces after a swallowed flush failure, causing the negative verification to fail.

- [ ] **Step 3: Require the attempt-scoped durable outcome**

  Replace `flushOk` with the outcome predicate:

  ```java
  boolean batchPersisted = false;
  ```

  Keep every current enqueue call unchanged. After all enqueue calls, consume the explicit flush outcome:

  ```java
  VectorStoreService.VectorFlushOutcome outcome = vectorStoreService.flush();
  batchPersisted = outcome.durable();
  ```

  Only inside `if (batchPersisted)` may the scheduler call `reinforceWithSnippet` and set `lastFetchTime` to the current time. On false or exception, do neither and record `indexing.flush.failed` plus a fixed reason code. Do not place raw document text or vector IDs in logs or traces.

- [ ] **Step 4: Replace the false-green source contract with a behavior-backed contract**

  In `OperationalQualitySourceContractTest`, remove the assertion that the text order `flush();` followed by `flushOk = true` proves success. Retain redaction and non-advance assertions that remain meaningful, and assert `outcome.durable()` is the supporting ownership check. The new `IndexingSchedulerBehaviorTest` remains the decisive proof.

- [ ] **Step 5: Run focused GREEN and the affected source contract**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.scheduler.IndexingSchedulerBehaviorTest" `
    --tests "com.example.lms.service.OperationalQualitySourceContractTest" `
    --no-daemon
  ```

  Expected: no reinforcement or cursor movement for backoff, partial, or failed outcomes; durable success performs both exactly once.

---

### Task 4: Atomically claim one image job before provider work

**Files:**

- Modify: `main/java/com/example/lms/plugin/image/jobs/ImageJobRepository.java:1-36`
- Modify: `main/java/com/example/lms/plugin/image/jobs/ImageJobService.java:157-183`
- Create: `src/test/java/com/example/lms/plugin/image/jobs/ImageJobRepositoryClaimTest.java`
- Create: `src/test/java/com/example/lms/plugin/image/jobs/ImageJobServiceClaimTest.java`
- Modify: existing image-job service tests only where their repository mocks must model the new claim/fetch sequence

**Interfaces:**

- Consumes: existing oldest-PENDING finder, string job ID, enum-string status, `startedAt`, and progress.
- Produces:

  ```java
  int claimIfStatus(
      String id,
      ImageJob.Status expectedStatus,
      ImageJob.Status claimedStatus,
      Instant startedAt,
      Integer progress);
  ```

- [ ] **Step 1: Resolve the untracked repository ownership gate**

  Immediately before editing, run:

  ```powershell
  git status --short -- main/java/com/example/lms/plugin/image/jobs/ImageJobRepository.java
  git ls-files --error-unmatch main/java/com/example/lms/plugin/image/jobs/ImageJobRepository.java
  Get-FileHash -LiteralPath main/java/com/example/lms/plugin/image/jobs/ImageJobRepository.java -Algorithm SHA256
  ```

  If the file is still untracked and no current source-owner evidence assigns it to this task, record `HOLD` for item 5 with `repositoryWideHold=false` and continue other cohorts. Do not overwrite or adopt the file by inference.

- [ ] **Step 2: Write repository and service RED tests**

  In `ImageJobRepositoryClaimTest`, persist one `PENDING` job and assert:

  ```java
  assertThat(repository.claimIfStatus(id, PENDING, IN_PROGRESS, startedAt, 10)).isEqualTo(1);
  assertThat(repository.claimIfStatus(id, PENDING, IN_PROGRESS, startedAt, 10)).isZero();
  assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(IN_PROGRESS);
  ```

  In `ImageJobServiceClaimTest`, return the same candidate from the finder but `0` from `claimIfStatus`; assert no `manifestWriter.write`, no `imageService.generateImages`, and no terminal save. For the winning case return `1`, return the freshly claimed job from `findById`, and assert exactly one provider invocation.

- [ ] **Step 3: Run the claim tests and verify RED**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.plugin.image.jobs.ImageJobRepositoryClaimTest" `
    --tests "com.example.lms.plugin.image.jobs.ImageJobServiceClaimTest" `
    --no-daemon
  ```

  Expected: compilation failure because `claimIfStatus` does not exist, followed by the current service invoking provider work without an atomic claim.

- [ ] **Step 4: Add the conditional repository update**

  Add the existing Spring Data annotations and this JPQL update:

  ```java
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      update ImageJob j
         set j.status = :claimedStatus,
             j.startedAt = :startedAt,
             j.progress = :progress
       where j.id = :id
         and j.status = :expectedStatus
      """)
  int claimIfStatus(
          @Param("id") String id,
          @Param("expectedStatus") ImageJob.Status expectedStatus,
          @Param("claimedStatus") ImageJob.Status claimedStatus,
          @Param("startedAt") Instant startedAt,
          @Param("progress") Integer progress);
  ```

  Do not add a process-local `synchronized` substitute; it cannot protect multiple nodes.

- [ ] **Step 5: Require claim success before manifest and provider work**

  Keep the oldest-PENDING read as a candidate selection only. Then:

  ```java
  Instant startedAt = Instant.now();
  int claimed = jobRepo.claimIfStatus(
          candidate.getId(), ImageJob.Status.PENDING,
          ImageJob.Status.IN_PROGRESS, startedAt, 10);
  if (claimed != 1) {
      return;
  }
  ImageJob job = jobRepo.findById(candidate.getId()).orElse(null);
  if (job == null) {
      return;
  }
  ```

  Only after this block may the service write the manifest or call the image provider. Preserve terminal-state, storage, redaction, diagnostics, and ETA behavior.

- [ ] **Step 6: Run GREEN and existing image regressions**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.plugin.image.jobs.ImageJobRepositoryClaimTest" `
    --tests "com.example.lms.plugin.image.jobs.ImageJobServiceClaimTest" `
    --tests "com.example.lms.plugin.image.jobs.ImageJobServiceDebugDiagnosticsTest" `
    --tests "com.example.lms.plugin.image.jobs.ImageJobServicePathRedactionTest" `
    --tests "com.example.lms.plugin.image.ImageJobServiceSourceContractTest" `
    --no-daemon
  ```

  Expected: conditional claim is one-winner, a lost claim produces zero paid-provider calls, and all existing diagnostics/redaction behavior remains green.

---

### Task 5: Verify Cohort A across its affected boundaries

**Files:**

- Verify: every production/test file listed above
- Update after evidence only: the current Priority-100 execution ledger or final report; do not alter the approved spec's audit inventory

**Interfaces:**

- Consumes: focused GREEN output, exact postimage hashes, target diff, source-set checks, host-isolated Gradle output, and source-edit lease state.
- Produces: evidence-backed dispositions for items 3, 4, and 5; item 2 remains open unless queue capacity is separately proven/fixed.

- [ ] **Step 1: Run all Cohort A focused tests together**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.service.VectorStoreServiceFlushOutcomeTest" `
    --tests "com.example.lms.scheduler.PendingMemorySoakSchedulerBehaviorTest" `
    --tests "com.example.lms.scheduler.IndexingSchedulerBehaviorTest" `
    --tests "com.example.lms.plugin.image.jobs.ImageJobRepositoryClaimTest" `
    --tests "com.example.lms.plugin.image.jobs.ImageJobServiceClaimTest" `
    --tests "com.example.lms.scheduler.SchedulerRedactionContractTest" `
    --tests "com.example.lms.service.OperationalQualitySourceContractTest" `
    --tests "com.example.lms.service.VectorStoreServiceLmsDiagnosticsRedactionTest" `
    --tests "com.example.lms.plugin.image.jobs.ImageJobServiceDebugDiagnosticsTest" `
    --tests "com.example.lms.plugin.image.jobs.ImageJobServicePathRedactionTest" `
    --tests "com.example.lms.plugin.image.ImageJobServiceSourceContractTest" `
    --no-daemon
  ```

- [ ] **Step 2: Run the active-source and compile boundaries**

  ```powershell
  .\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes `
    --no-daemon
  ```

  Expected: all tasks pass with LangChain4j exactly `1.0.1` and no new duplicate owner.

- [ ] **Step 3: Broaden only because shared persistence/orchestration changed**

  Use Desktop-isolated caches and force fresh test classes:

  ```powershell
  $env:AWX_SPLIT_BUILD_OUTPUTS = '1'
  $env:AWX_BUILD_HOST_ID = 'desktop'
  $taskGradleHome = Join-Path $env:LOCALAPPDATA 'awx-gradle-priority100-a'
  $taskProjectCache = Join-Path $env:LOCALAPPDATA 'awx-project-cache-priority100-a'
  $env:GRADLE_USER_HOME = $taskGradleHome
  .\gradlew.bat test --rerun-tasks --fail-fast --no-daemon `
    --project-cache-dir $taskProjectCache
  .\gradlew.bat bootJar -x test --no-daemon `
    --project-cache-dir $taskProjectCache
  ```

  If the broad run exhibits stale `NoClassDefFoundError` or `ClassNotFoundException` while current classes exist under `build\desktop\classes`, run `scripts\verify_full_test_refresh.ps1` once; do not reinterpret stale default output as a source regression.

- [ ] **Step 4: Inspect the exact final diff and privacy boundary**

  ```powershell
  git diff --check -- `
    main/java/com/example/lms/service/VectorStoreService.java `
    main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java `
    main/java/com/example/lms/scheduler/IndexingScheduler.java `
    main/java/com/example/lms/plugin/image/jobs/ImageJobRepository.java `
    main/java/com/example/lms/plugin/image/jobs/ImageJobService.java `
    src/test/java/com/example/lms/service/VectorStoreServiceFlushOutcomeTest.java `
    src/test/java/com/example/lms/scheduler/PendingMemorySoakSchedulerBehaviorTest.java `
    src/test/java/com/example/lms/scheduler/IndexingSchedulerBehaviorTest.java `
    src/test/java/com/example/lms/plugin/image/jobs/ImageJobRepositoryClaimTest.java `
    src/test/java/com/example/lms/plugin/image/jobs/ImageJobServiceClaimTest.java
  git diff --stat -- `
    main/java/com/example/lms/service/VectorStoreService.java `
    main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java `
    main/java/com/example/lms/scheduler/IndexingScheduler.java `
    main/java/com/example/lms/plugin/image/jobs/ImageJobRepository.java `
    main/java/com/example/lms/plugin/image/jobs/ImageJobService.java `
    src/test/java/com/example/lms/service/VectorStoreServiceFlushOutcomeTest.java `
    src/test/java/com/example/lms/scheduler/PendingMemorySoakSchedulerBehaviorTest.java `
    src/test/java/com/example/lms/scheduler/IndexingSchedulerBehaviorTest.java `
    src/test/java/com/example/lms/plugin/image/jobs/ImageJobRepositoryClaimTest.java `
    src/test/java/com/example/lms/plugin/image/jobs/ImageJobServiceClaimTest.java
  ```

  Inspect every hunk. Run the repository's count-only secret guard against only these targets:

  ```powershell
  $changedTargets = @(
    'main/java/com/example/lms/service/VectorStoreService.java',
    'main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java',
    'main/java/com/example/lms/scheduler/IndexingScheduler.java',
    'main/java/com/example/lms/plugin/image/jobs/ImageJobRepository.java',
    'main/java/com/example/lms/plugin/image/jobs/ImageJobService.java',
    'src/test/java/com/example/lms/service/VectorStoreServiceFlushOutcomeTest.java',
    'src/test/java/com/example/lms/scheduler/PendingMemorySoakSchedulerBehaviorTest.java',
    'src/test/java/com/example/lms/scheduler/IndexingSchedulerBehaviorTest.java',
    'src/test/java/com/example/lms/plugin/image/jobs/ImageJobRepositoryClaimTest.java',
    'src/test/java/com/example/lms/plugin/image/jobs/ImageJobServiceClaimTest.java'
  )
  & powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\git_secret_guard.ps1 `
    -Mode manual -Path $changedTargets
  ```

  Expected: zero findings. No raw vector ID, session ID, content, prompt, path, provider result, or secret reaches a public trace/log/UI surface.

- [ ] **Step 5: Record dispositions and release the lease in `finally`**

  Mark item 3 `FIXED` only when a row stays PENDING through every non-durable outcome and reaches ACTIVE from the exact durable flush outcome. Mark item 4 `FIXED` only when failure/partial retry performs zero reinforcement and a durable outcome performs it once. Mark item 5 `FIXED` only when the database conditional update and zero-provider-call lost-claim tests pass; otherwise record its lane-local HOLD fields.

  Release the lease whether verification passes or aborts:

  ```powershell
  & powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
    -Action end -Role desktop -Root . `
    -Topic priority-100-cohort-a-durable-state `
    -OwnerId codex-root-priority-100-a
  ```

  Do not create a Git commit because commit authority is absent.

## Program Continuation

After this independently testable cohort is integrated, the approved design requires separate implementation plans for:

1. security/privacy boundaries (items 1, 6, 13, and 55-58);
2. cancellation/resource lifecycle and bounded retention (items 2, 7-12, and 19-26, 28);
3. user-visible correctness and sanitizer/fallback behavior (items 14-17 and 27-32, 80);
4. build/artifact/configuration ownership (items 33-60);
5. evidence/test hygiene (items 61-81 excluding 80);
6. structural concentration and parallel ownership (items 82-100).

Each plan must revalidate its own active preimages and produce working, independently testable software or an exact terminal evidence disposition. Cohort A does not claim those items complete.
