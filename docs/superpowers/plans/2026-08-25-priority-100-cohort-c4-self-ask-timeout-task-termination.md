# Priority-100 Cohort C4 Self-Ask Timeout Task-Termination Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`. Repository policy keeps all writes, integration, verification claims, and final judgment in the parent Codex session; subagents remain bounded read-only analysts/reviewers.

**Goal:** Close authoritative rank 9 by making a timed-out Self-Ask provider attempt receive a real cooperative interrupt despite the enabled-by-default `CancelShield` wrapper, proving that an executing interruptible attempt terminates and the same constrained executor can run its next task, while retaining bounded fail-soft results, request interrupt preservation, context propagation, provider I/O timeouts, and redacted diagnostics.

**Architecture:** Keep the shared Spring-owned `searchIoExecutor`; add no executor, thread factory, dependency, provider API, or global cancellation exception. Self-Ask will own each submitted `FutureTask<SearchAttempt>` and pass it through `ExecutorService.execute(...)`, which preserves the existing executor/context wrappers but avoids accepting the shielded `Future` returned by `submit(...)`. The retained caller-owned `FutureTask` therefore honors `cancel(true)` for this one task. Deadline, hard-timeout, interrupted-wait, and straggler paths cancel that task interruptibly. `InterruptedException` on the request waiter restores the request flag and stops further Self-Ask provider/Tavily expansion. A real one-thread, CancelShield-wrapped executor test must observe provider entry, cancellation-induced task exit, and execution of a sentinel on the same worker before any manual release. The repair makes a cooperative cancellation guarantee; non-interruptible network I/O remains bounded by the existing provider connect/read timeouts and is not claimed to be forcibly killable by Java interruption.

**Tech Stack:** Java 17 `FutureTask`/interrupt semantics, Spring `ExecutorService`, existing `ContextAwareExecutorService` and `CancelShieldExecutorService`, JUnit 5, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`, cancellation contract at line 236 and authoritative rank 9 at line 326.

## Scope and constraints

- Active production owners are `main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java` and `main/java/com/example/lms/service/rag/SelfAskTimeoutTrace.java`. Do not change `SearchExecutorConfig`, `ContextAwareExecutorService`, `CancelShieldExecutorService`, `CancelShieldFuture`, `HybridWebSearchProvider`, or any provider implementation in this cohort.
- Active focused tests are `src/test/java/com/example/lms/service/rag/SelfAskWebSearchRetrieverTest.java` and `src/test/java/com/example/lms/service/rag/SelfAskTimeoutTraceTest.java`.
- Frozen planning hashes are retriever `ED735CAAD141F0A05B9D9BA7AAB7DC9C3A726C8480AE068736A9018BFBE7BD10`, timeout trace `254BFD9FB7375036EC33E583E1DB4E60B1D6B5CD84F6D2C08968531140F2C1BE`, retriever test `34A716B2802C26E642DEDB8E016852385957F83F7B2A527A62D0557AEC6388C7`, and timeout-trace test `9177BA76183B3C9CC02240703541DA571A05DACF0D495EE1450959C3CB6949C4`. The retriever and its direct test are tracked and already user-dirty with unrelated creative/Self-Ask policy work; the trace pair is clean. Freeze all four again before every hunk and preserve all unrelated content.
- Current focused baseline is 43 tests, 0 failures, 0 errors, 0 skipped across the two fixtures.
- `searchIoExecutor.submit(...)` is not an acceptable repair seam: `ContextAwareExecutorService` and the enabled-by-default post-processor return a `CancelShieldFuture` that downgrades `cancel(true)` to `cancel(false)`. The plan must retain an unshielded task-owned handle without unwrapping or mutating the shared executor.
- Use exactly one caller-owned `FutureTask<SearchAttempt>` per initial or retry attempt. Submit it once with `searchExecutor.execute(task)` and return that same task handle. Do not create a second executor, raw thread, scheduler, semaphore framework, cancellation token hierarchy, or task registry.
- `cancel(true)` is allowed only on these Self-Ask-owned task handles. Do not weaken or bypass `CancelShield` for other executor clients and do not modify its global allowlist/policy.
- A pre-start cancellation must prevent provider invocation. An in-flight cooperative cancellation must cause the attempt to exit, and the constrained shared executor must accept and execute a later sentinel. A fake `Future.cancel(true)` assertion alone is insufficient.
- Preserve provider-level connect/read timeout configuration. Do not claim that Java interruption forcibly terminates arbitrary non-interruptible socket I/O; the verified claim is interrupt delivery plus real capacity recovery for an interruptible provider boundary.
- Restore the request thread with `Thread.currentThread().interrupt()` after a caught `InterruptedException`. Never call `Thread.interrupted()` in production; tests may clear the JUnit thread only in `finally`.
- Once request interruption is restored, cancel all outstanding Self-Ask tasks interruptibly and return already-collected snippets (or the existing empty fallback) without retry generation, follow-up LLM work, Tavily fallback, or any new provider submission.
- Timeout telemetry must describe the new behavior literally: fixed/redacted stage, `cancelRequested=true`, `cancelInterrupt=true`, cancellation outcome, caller-interrupted boolean, bounded timeout, and query hash only. Remove false `cancelSuppressed`/`interruptCleaned` claims from this component; repository search shows no production consumer of those Self-Ask-specific keys.
- Do not log or trace raw query, result, provider response, session identifier, exception message, credential, header, or executor/thread identity.
- Do not stage, commit, push, merge, deploy, mutate databases/credentials, clean worktrees, add dependencies, or touch live provider/runtime processes.

## Enumerated production surface

1. Initial fan-out submission near retriever line 475.
2. Branch-retry submission near line 546.
3. Per-depth straggler cancellation near lines 596-600.
4. `getWithHardTimeout(...)` deadline-exhausted, hard-timeout, interrupted-wait, execution-failure, and generic-failure paths near lines 2121-2155.
5. `safeSearchAttempt(...)` pre/post provider interrupt observation near lines 2162-2176.
6. `SelfAskTimeoutTrace` cancellation evidence at lines 10-20.

---

### Task 0: Freeze ownership and pass the application-source gate

- [x] **Step 1: Refresh repository and overlap gates**

  Record Java 17, branch/HEAD, worktrees, exact four-target status/hashes, index lock, top-level PatchDrop state, active source-edit leases, and target-relevant writer evidence. Run Desktop-isolated `checkSourceSetHygiene` and `checkLangchain4jVersionPurity`. The large dirty tree is not a repository-wide hold; a changed frozen target, active overlapping writer/lease, index lock, or ambiguous top-level patch holds only C4.

- [x] **Step 2: Freeze the active cancellation chain**

  Confirm both Self-Ask submission sites still call `searchExecutor.submit`, all current cancellation sites still use `cancel(false)`, `searchIoExecutor` is Spring-owned/shared, active wrappers shield returned submit futures, and `execute(Runnable)` preserves context without replacing the caller's `FutureTask` handle. Record normal profile enablement plus plan-override reachability; disabled-by-profile does not make rank 9 dead code.

- [x] **Step 3: Freeze and adjudicate the required three-way packets**

  Freeze at most 20 redacted evidence rows for scenario `S_SELFASK_TIMEOUT_TASK_TERMINATION`. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`; repeat neutral adjudication with support packets in A-B and B-A order. Require stable `APPLY`, identical decisive evidence, and score at least 50. The negative packet must challenge whether a raw `cancel(true)` is neutralized by CancelShield, whether a new executor merely moves the leak, whether a caller-owned `FutureTask` passed through `execute(...)` really preserves context and receives interruption, and whether the proposed test proves physical worker reuse rather than only `Future.isDone()`.

- [x] **Step 4: Acquire the Desktop source-edit lease**

  Use topic `priority-100-cohort-c4-self-ask-timeout-task-termination` and owner `codex-root-priority-100-c4`. Re-hash all four targets after acquisition and immediately before each test/source hunk.

---

### Task 1: Prove the live cancellation failure with focused RED tests

**Files:** `src/test/java/com/example/lms/service/rag/SelfAskWebSearchRetrieverTest.java`, `src/test/java/com/example/lms/service/rag/SelfAskTimeoutTraceTest.java`

- [x] **Step 1: Add the real constrained-executor RED case**

  Build a one-thread raw executor and wrap it with the existing `ContextAwareExecutorService` and `CancelShieldExecutorService` in the live order. Inject that executor into a retriever whose first synchronous provider call returns empty and whose one budgeted asynchronous call signals `started`, then blocks on an interruptible latch. Configure `maxApiCallsPerQuery=1`, one depth, a short per-request timeout, no Tavily, and no optional LLM follow-ups.

  Invoke `retrieve(...)`, wait for the async provider to start, let the hard timeout return, and then submit a sentinel to the same one-thread executor. Before any manual provider release, require all of the following under bounded latches/timeouts: the provider observed interruption, the running attempt exited, the sentinel ran, no second asynchronous provider call was admitted, and the result remained the established empty/partial fail-soft value. The current implementation must fail because its shielded submit future and `cancel(false)` leave the one worker occupied. Cleanup releases the provider and shuts down only the test-owned executor in `finally`.

- [x] **Step 2: Add request-interrupt and telemetry RED cases**

  Use an existing/refined recording `Future` whose timed `get` throws `TimeoutException` to require one `cancel(true)`, `cancelInterrupt=true`, fixed `hard_timeout`, a bounded timeout value, a 12-character query hash, and no raw query. Add a second recording future whose timed `get` throws `InterruptedException`; require the returned `interrupted` fail-soft attempt, one `cancel(true)`, restored caller interrupt, and literal caller-interrupted telemetry. Clear the test thread only in `finally`.

  Add a retrieval-level request-interrupt RED with a separate test-owned waiter thread. The first synchronous provider call returns empty. Permit two asynchronous attempts on a one-worker CancelShield-wrapped executor: the first signals entry and blocks interruptibly; the second remains queued. After the first async entry is observed, interrupt the waiter while it is inside `getWithHardTimeout(...)`. The provider records the delivered interrupt and delays its final return only until a latch set by the waiter's return boundary, preventing the worker from racing into the queued sibling before the caller can cancel it. Require under bounded waits that the waiter captures `Thread.currentThread().isInterrupted()==true` immediately after `retrieve(...)` returns, the running attempt observed interruption and exited, the queued sibling never invoked the provider, a sentinel subsequently ran on the same worker, the result is the existing empty/partial fallback, and branch retry, follow-up generation, next-depth work, and Tavily calls remain zero. The manual provider-release latch is fallback cleanup in `finally` only and must not be used to obtain GREEN.

  Update `SelfAskTimeoutTraceTest` to require sanitized stage labels and the new literal cancellation keys without the raw secret. Update source-stability assertions to require both fan-out sites to use the task-owned submission helper, production `cancel(false)` count zero within `SelfAskWebSearchRetriever`, and no production `Thread.interrupted()`.

- [x] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.service.rag.SelfAskWebSearchRetrieverTest" `
    --tests "com.example.lms.service.rag.SelfAskTimeoutTraceTest" `
    --no-daemon `
    --project-cache-dir "C:\AbandonWare\gradle-project-cache\codex-priority100-rank9"
  ```

  Expected: only new termination/capacity, `cancel(true)`, request-interrupt-preservation, and literal telemetry assertions fail. Existing 43 tests remain green or are changed only where their old assertions explicitly enshrine the defect.

---

### Task 2: Implement the smallest full-surface repair

**Files:** `main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java`, `main/java/com/example/lms/service/rag/SelfAskTimeoutTrace.java`

- [x] **Step 1: Retain a real task-owned cancellation handle**

  Add one private `submitSearchAttempt(String query, int topK)` helper. Construct `FutureTask<SearchAttempt>` from `safeSearchAttempt(query, topK)`, call `searchExecutor.execute(task)` exactly once, and return the same task. Replace only the initial fan-out and branch-retry `submit(...)` calls with this helper. Do not unwrap the executor and do not catch/reclassify `RejectedExecutionException` beyond existing fail-soft behavior.

- [x] **Step 2: Request interruptible cancellation at every owned timeout seam**

  Add one narrow `cancelSearchAttempt(Future<SearchAttempt>)` helper that requests `future.cancel(true)` only when the handle is non-null and unfinished and returns whether cancellation was accepted. Use it for deadline exhaustion, hard timeout, interrupted wait, generic get failure, and level stragglers. ExecutionException means the task is already terminal and does not require a fabricated cancellation success. Replace comments that describe `cancel(false)` as zombie prevention.

  In `safeSearchAttempt`, check `Thread.currentThread().isInterrupted()` immediately before provider invocation and again before accepting provider output, returning the established fail-soft cancellation classification without clearing the flag. This guarantees pre-start/late-result suppression; it does not pretend to kill a non-interruptible provider implementation.

- [x] **Step 3: Preserve caller interruption and stop downstream expansion**

  In the `InterruptedException` catch, restore the caller flag before returning the current fail-soft attempt and cancel the owned provider task interruptibly. In `retrieve(...)`, immediately after every initial `getWithHardTimeout(...)` result, inspect `Thread.currentThread().isInterrupted()` without clearing it. When true, cancel the current handle and every unfinished sibling in that level's `futures` list with `cancelSearchAttempt(...)`, then return already-collected content (or the existing empty fallback) before branch-quality retry generation, follow-up generation, a new depth, or Tavily.

  Apply the same exact control point immediately after `getWithHardTimeout(retryFuture, ...)`: if interruption is observed, cancel the retry handle plus every unfinished original-level sibling and return the same partial/empty fallback before retry result processing, follow-ups, another depth, or Tavily. Keep the flag set across the return boundary. Do not infer interruption only from the `SearchAttempt.failureClass`; the decisive signal is the non-clearing request-thread flag.

- [x] **Step 4: Make telemetry literal and redacted**

  Replace `recordCancelSuppressed(...)` with a narrowly named cancellation-record method taking fixed stage, timeout, query, caller-interrupted, and cancellation-accepted values. Record only stage/error label, `cancelRequested=true`, `cancelInterrupt=true`, `cancelAccepted`, `callerInterrupted`, non-negative timeout, and `SafeRedactor.hash12(query)`. Remove this component's false `cancelSuppressed` and `interruptCleaned` values.

- [x] **Step 5: Run focused GREEN and inspect invariants**

  Re-run both focused fixtures and inspect XML totals, exact four-target diff, postimage hashes, both submission sites, every cancellation site, interrupt reads/writes, and all trace keys. Required static invariants in the retriever: both asynchronous attempts use `submitSearchAttempt`; production `cancel(false)` count 0; production `Thread.interrupted()` count 0; no new executor/thread construction; raw query still appears only as input to `SafeRedactor.hash12` or provider logic, never telemetry.

---

### Task 3: Review, verify, and record rank 9

- [x] **Step 1: Run affected boundaries**

  Locate and run active Self-Ask, timeout-trace, `ContextAwareExecutorServiceCancelShieldTest`, `CancelShieldExecutorServicePostProcessorTest`, and relevant `HybridWebSearchProvider` interrupt fixtures. The boundary proof must show global CancelShield behavior remains unchanged while the Self-Ask task-owned handle regains the same constrained worker. Record exact suite/test/failure/error/skipped totals. No real provider or credential call is allowed.

- [x] **Step 2: Request independent implementation review**

  The reviewer receives the approved plan hash, frozen preimages, exact C4-only change journal/diff, RED/GREEN output, latch topology, postimage hashes, static submit/cancel/interrupt counts, and privacy scan. Require `SPEC`, `QUALITY`, Critical/Important findings, and `APPROVE|HOLD|REJECT`. Any Critical or Important finding gets its own focused RED before correction.

- [x] **Step 3: Broaden verification proportionally**

  Run Desktop-isolated `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes`, then host-isolated full `test --rerun-tasks --fail-fast` and fresh `bootJar`. Browser/provider-wire proof is not required for a deterministic Java executor-cancellation contract and must not be substituted for the real constrained-executor test.

- [x] **Step 4: Integrity and terminal disposition**

  Run exact-target `git diff --check`, postimage hashes, and a count-only secret scan. Record rank 9 as `FIXED` only after approved review, real capacity-recovery proof, and fresh focused/broad verification. Release the source-edit lease in `finally`.
