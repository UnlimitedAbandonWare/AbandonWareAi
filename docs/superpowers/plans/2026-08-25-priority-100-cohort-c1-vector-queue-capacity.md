# Priority-100 Cohort C1 Vector Queue Capacity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`. Repository policy keeps all writes, integration, verification claims, and final judgment in the parent Codex session; subagents remain bounded read-only analysts/reviewers.

**Goal:** Close authoritative rank 2 by placing a hard, concurrency-correct bound on all accepted `VectorStoreService` buffer entries, including entries temporarily owned by an in-flight flush, without silently converting rejected durable work into success.

**Architecture:** `VectorStoreService` keeps its atomic live-map swap, but one short-lived queue-state mutex accounts for both the live map and the snapshot currently being flushed. A new unique entry is accepted only while `live + inFlight` is below the configured capacity. A successfully stored batch releases its in-flight capacity; a failed snapshot moves back to the live map without acquiring capacity again. A newer same-ID live entry wins exactly as it does today, and the superseded failed snapshot releases its one retained slot. Capacity rejection throws one dedicated detail-free exception so durable callers cannot promote state or advance cursors after a silent drop. Count-only buffer diagnostics expose capacity and cumulative rejection state.

**Tech Stack:** Java 17, Spring `@Value`, `ConcurrentHashMap`, JUnit 5, LangChain4j test doubles, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`

## Scope and constraints

- Active owners are root `main/java`, `main/resources`, and `src/test/java`.
- This plan covers rank 2 only. It does not choose shutdown-drain semantics, change provider/executor cancellation, modify vector IDs or quarantine/shadow routing, add a durable queue/DLQ, or edit inactive `test/java` sources.
- `main/java/com/example/lms/service/VectorStoreService.java` is heavily user-dirty and contains Cohort A's `VectorFlushOutcome` contract. Re-hash immediately before every hunk and preserve all unrelated lines.
- `src/test/java/com/example/lms/service/VectorStoreServiceFlushOutcomeTest.java` is an untracked Cohort A test owned by this active goal. Preserve its existing six outcome/privacy tests and add capacity cases without replacing the fixture.
- Default capacity is `2_048`, four default 512-entry batches. The tracked Java `@Value` declaration owns the default; the ignored local `main/resources/application.properties` file remains unchanged and is not a deliverable. A missing, zero, or negative injected value falls back to 2,048; the effective capacity is always positive.
- The bound and public pending diagnostics cover every accepted entry retained in memory, whether it is in the live queue or in the snapshot currently undergoing embedding/store I/O. Automatic enqueue-side flush triggering uses only the current live-map size. The admission mutex is always released before a synchronous `flush()` call; therefore an enqueue may preserve today's behavior of waiting behind an already-running flush, but it cannot form a service-monitor/queue-mutex cycle. The mutex must never cover embedding generation, store calls, logging, trace emission, hashing, chunking, DLQ writes, or other unbounded work.
- A duplicate ID already present in the live queue consumes no new slot and remains accepted under saturation. A same-ID enqueue while an older version is in-flight is a distinct retained entry, matching today's atomic-swap behavior.
- When capacity is exhausted, reject only a new unique entry with `VectorQueueCapacityExceededException`. Its message and fields may contain the configured capacity but must not contain vector ID, session ID, text, metadata, trace/request ID, provider error, or other payload details.
- Capacity rejection must not be reported as success. Existing Cohort A callers catch enqueue failure or suppress durable promotion/cursor advancement when no durable outcome exists. No caller contract is broadened in C1.
- Rejection observability is count-only: expose effective capacity, in-flight count, and cumulative rejected count in `VectorBufferStats`; increment a safe trace counter/reason. Any warning must be rate-limited by cumulative count and contain counts only.
- Do not stage, commit, push, merge, deploy, call providers, mutate a live database, delete files, add dependencies, or clean worktrees/build outputs.

---

## File structure

### Production files

- Modify `main/java/com/example/lms/service/VectorStoreService.java`: capacity property, queue-state mutex/accounting, bounded admission, success release, failure restore, safe exception, and count-only diagnostics.
- Leave ignored/untracked `main/resources/application.properties` unchanged. The default is delivered by `@Value("${vectorstore.queue.max-pending:2048}")` in the tracked Java owner.

### Test files

- Modify `src/test/java/com/example/lms/service/VectorStoreServiceFlushOutcomeTest.java`: saturation, in-flight accounting, failed restore, recovery, dedupe, safe exception, and diagnostics.
- Modify `src/test/java/com/example/lms/scheduler/PendingMemorySoakSchedulerBehaviorTest.java`: use the concrete capacity exception to prove the pending row remains retryable.
- Modify `src/test/java/com/example/lms/scheduler/IndexingSchedulerBehaviorTest.java`: prove capacity rejection does not reinforce or advance the source cursor.

### Existing affected boundaries to retain

- all active `src/test/java/com/example/lms/service/VectorStoreService*Test.java` tests.

---

### Task 0: Freeze ownership and pass the application-source gate

- [x] **Step 1: Refresh repository gates**

  Record Java/Gradle versions, branch, HEAD, worktrees, exact target status/hashes, index lock, top-level PatchDrop state, and source-edit leases. Re-run `checkSourceSetHygiene` and `checkLangchain4jVersionPurity` with Desktop split outputs.

- [x] **Step 2: Freeze exact overlap and active call behavior**

  Confirm the planned insertion points at the current field block, enqueue admission line, `pendingSize`/`bufferStats`, flush swap, successful `addAll`, and failure restore loop. Record that the active scheduler/producer call paths use root `VectorStoreService`, and that inactive `test/java` reflection tests are outside the test source set.

- [x] **Step 3: Freeze and adjudicate the required three-way packets**

  Freeze at most 20 redacted evidence rows for scenario `S_VECTOR_QUEUE_CAPACITY`. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`; repeat neutral adjudication with support packets in A-B and B-A order. Require stable `APPLY`, identical decisive evidence, and score at least 50.

- [x] **Step 4: Acquire the Desktop source-edit lease**

  Use topic `priority-100-cohort-c1-vector-queue-capacity` and owner `codex-root-priority-100-c1`. Re-hash all three targets after acquisition and immediately before the first source hunk. HOLD rank 2 only on changed preimage, target writer/lease conflict, index lock, active top-level PatchDrop ambiguity, or failed neutral adjudication.

---

### Task 1: Prove the unbounded and in-flight gaps with focused RED tests

**File:** `src/test/java/com/example/lms/service/VectorStoreServiceFlushOutcomeTest.java`

- [x] **Step 1: Add deterministic capacity fixtures and RED cases**

  Extend the existing fixture with field setters and a store that can fail once or block `addAll` with `CountDownLatch`. Add tests proving:

  1. With capacity 3 and automatic flush disabled by a deterministic backoff gate, three unique live entries are retained, a fourth unique enqueue throws the dedicated capacity exception, pending count never exceeds 3, and the exception/diagnostics contain no fixture ID, session, or text. This case characterizes live-map saturation only; the separate latch case characterizes in-flight accounting.
  2. Re-enqueueing an ID already present in the live queue while saturated does not throw, does not grow pending count, and does not increment the rejected counter.
  3. With capacity 2, two entries moved into a latch-blocked in-flight flush still consume both slots even though the live map is empty; a third unique enqueue is rejected. After the store succeeds, capacity is released and the third entry can be accepted. The rejection must return without trying to enter a second synchronized flush.
  4. A deterministic first store failure restores the bounded snapshot, returns non-durable `store_failure`, and leaves exactly the configured number pending. After backoff is cleared and the store succeeds, the queue drains and a new entry is accepted.
  5. With capacity 3, move old `A` and `B` into a latch-blocked snapshot, admit newer `A` into the new live map after the swap, and force store failure. Assert the newer enqueue completes before the store latch is released, proving it did not synchronously queue behind the running flush. Then assert restore preserves newer `A`, restores only `B`, leaves `live + inFlight == 2` and `inFlight == 0`, admits exactly one further unique entry, and rejects the next. A later successful flush must store newer `A`, never the superseded old `A`.
  6. Add a partial-success same-ID variant with capacity 4 and two trace groups: move old `A` and `B` in-flight, admit newer live `A` and `B`, and prove both enqueues complete before releasing the store latch even though total retained reaches capacity. Let one old group store successfully and force the later group to fail. Assert each successful entry releases exactly one slot, each remaining entry is either restored or superseded exactly once, the two newer live values survive, `inFlight == 0`, and the remaining two slots are usable with no leak or double release. The test must not assume `ConcurrentHashMap` iteration order; the fake store records which old group succeeded/failed and assertions follow the observed IDs.
  7. With capacity 4 and `batchSize=2`, block flush F1 after one entry becomes in-flight. Enqueue one live ID, then submit the second threshold-crossing enqueue on another owned test thread. Assert it is waiting while F1's store latch remains closed, release F1, and assert both F1 and the threshold enqueue finish within the bounded timeout and the second serialized flush drains the live entries. This proves the enqueue calls `flush()` only after releasing the queue mutex: temporary service-monitor waiting is allowed, circular waiting is not. The test must not rely on either scheduler.
  8. A zero or negative injected capacity resolves to the positive default rather than disabling the bound or reducing it to an accidental one-entry limit.

  Update `PendingMemorySoakSchedulerBehaviorTest.enqueueThrowReleasesPendingLeaseAndDoesNotFlush` to throw the concrete capacity exception and retain its exact `PENDING`/lease-release/no-flush assertions. Add an `IndexingSchedulerBehaviorTest` case whose vector service throws the concrete capacity exception during enqueue; two scheduler runs must use the same fetch cursor, never reinforce, and never flush.

  Every concurrency test must use latches, a bounded JUnit timeout, and `finally` cleanup; do not add sleeps. No test may invoke a real embedding provider, network, database, or filesystem.

- [x] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.VectorStoreServiceFlushOutcomeTest" --no-daemon
  .\gradlew.bat test --tests "com.example.lms.scheduler.PendingMemorySoakSchedulerBehaviorTest" --tests "com.example.lms.scheduler.IndexingSchedulerBehaviorTest" --no-daemon
  ```

  Expected: compilation/assertion failures only for the missing capacity exception, stats fields, live-plus-in-flight bound, and concrete caller exception contract. Fixture or existing Cohort A outcome failures are not acceptable RED.

---

### Task 2: Add the smallest concurrency-correct queue bound

**Files:**

- `main/java/com/example/lms/service/VectorStoreService.java`

- [x] **Step 1: Add bounded state and safe observability**

  Add a `DEFAULT_QUEUE_MAX_PENDING = 2_048` constant, `@Value("${vectorstore.queue.max-pending:2048}")`, one private queue-state mutex, an in-flight entry counter guarded by that mutex, and an atomic cumulative rejection counter. `pendingSize()` must return `live map size + in-flight count` from one mutex-protected snapshot. Extend `VectorBufferStats` with effective capacity, in-flight count, and rejected count.

  Add a public nested `VectorQueueCapacityExceededException` whose message/fields expose only the capacity. On rejection, increment only count/reason telemetry and emit a count-only warning at exponentially sparse cumulative counts (1, 2, 4, 8, ...), outside the mutex.

- [x] **Step 2: Bound admission and preserve dedupe**

  Replace the bare `queueRef.get().putIfAbsent(...)` with one short mutex-held admission helper:

  - if the live map already contains the ID, retain current first-write dedupe semantics and consume no slot;
  - otherwise compare `live size + inFlight` with effective capacity;
  - insert and return when below capacity;
  - otherwise leave all maps/counters except rejection telemetry unchanged and throw the safe exception.

  The mutex-held admission helper returns an immutable result containing whether the ID was newly accepted, `liveSizeAfterAdmission`, and the effective threshold. It releases the queue-state mutex before any possible `flush()` call. No path may acquire the service `flush()` monitor while holding the queue-state mutex.

  Keep automatic flushing after admission, using `min(positive batch size, effective capacity)` as the threshold so a configured capacity below batch size remains usable. Invoke it only after the mutex is released and only when the captured live size reaches the threshold; do not compare with public `pendingSize()` or `live+inFlight`. When another flush is already running, the enqueue may block on the existing synchronized service monitor after releasing the queue mutex. This preserves scheduler-independent progress and current synchronous batch semantics without lock-order inversion.

- [x] **Step 3: Account for atomic swap, success, and restore**

  Under the queue-state mutex, swap the live map and add its size to `inFlight`. Release the mutex before grouping, embedding, or store I/O. Immediately after each successful `embeddingStore.addAll`, perform one short mutex-held completion transition that first marks exactly those IDs completed in `okIds` and then decrements exactly those entries from `inFlight`.

  In the failure path, use one short mutex-held restore pass for every ID not marked completed. Each remaining snapshot entry consumes its in-flight slot exactly once: `putIfAbsent` either moves it into the live map without acquiring a new slot or detects a newer same-ID live entry and discards the superseded snapshot; in both cases decrement that entry from `inFlight` exactly once. Completed IDs are never restored or decremented again. At exit, all successfully stored, restored, or superseded snapshot entries must have left `inFlight`, and `live + inFlight` must never exceed capacity.

- [x] **Step 4: Preserve the ignored local resource and deliver the default in tracked code**

  Keep ignored/untracked `main/resources/application.properties` at its exact preimage. Deliver the default only through the tracked Java placeholder `@Value("${vectorstore.queue.max-pending:2048}")`. Do not change batch size, flush schedule, backoff, provider, shadow, quarantine, or DLQ properties.

- [x] **Step 5: Run focused GREEN and inspect invariants**

  Re-run `VectorStoreServiceFlushOutcomeTest`. Inspect its XML totals, exact target diff, postimage hashes, and all queue mutation sites. Confirm no mutex covers `embedAll`, `addAll`, trace/log calls, chunking, or DLQ writes.

---

### Task 3: Integrate, review, verify, and record rank 2

- [x] **Step 1: Run affected-boundary tests**

  Run all active `VectorStoreService*Test` classes plus the real `PendingMemorySoakSchedulerBehaviorTest` and `IndexingSchedulerBehaviorTest`. Record exact XML suite/test/failure/error/skipped totals.

- [x] **Step 2: Request independent task and integrated review**

  Reviewers receive only the contract, exact diff, RED/GREEN evidence, pre/post hashes, and privacy evidence. Require `SPEC`, `QUALITY`, Critical/Important findings, and `APPROVE|HOLD|REJECT`. Any Critical/Important finding gets a focused RED before correction.

- [x] **Step 3: Broaden verification proportionally**

  Run Desktop-isolated `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes`, then host-isolated full `test --rerun-tasks --fail-fast`, followed by `bootJar` because the active production class changes. Inspect the packaged JAR for the exact Java capacity placeholder/default and capacity-exception class.

- [x] **Step 4: Integrity and terminal disposition**

  Run exact-target `git diff --check`, postimage hashes, and a count-only secret scan. Record rank 2 as `FIXED` only after approved review and fresh focused/broad proof. Record shutdown-drain semantics as a separate open lifecycle decision rather than overclaiming it as part of rank 2. Release the source-edit lease in `finally`.
