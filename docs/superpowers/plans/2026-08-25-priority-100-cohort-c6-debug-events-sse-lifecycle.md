# Priority-100 Cohort C6: Debug Events SSE Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`. Repository policy keeps all writes, integration, verification claims, and final judgment in the parent Codex session; subagents remain bounded read-only analysts/reviewers.

**Goal:** Close authoritative rank 11 by giving every accepted admin debug-event SSE stream one bounded, Spring-owned worker task, a finite emitter lifetime, prompt idempotent cancellation on every servlet terminal callback, fail-fast capacity admission, and deterministic application shutdown.

**Architecture:** Replace the controller's static cached pool with one Spring `@Component` runtime that owns a zero-queue bounded `ThreadPoolExecutor` and shuts it down with `@PreDestroy`. The runtime intentionally does not implement/expose `ExecutorService`, so global executor post-processors cannot replace the controller's cancellation handle. Each stream creates one caller-owned `FutureTask`, attaches it to one idempotent `StreamSession`, and passes it through the runtime's `execute`; completion/timeout/error callbacks cancel that exact task with `cancel(true)`, waking `Thread.sleep`. A finite configured emitter timeout is normalized to a safe bounded range. Saturated admission throws immediately from `SynchronousQueue`/`AbortPolicy`; the returned emitter is completed with one fixed safe overload reason and is never queued. Worker exit completes the emitter once unless a servlet callback already made it terminal.

**Tech Stack:** Java 17 `FutureTask`/`ThreadPoolExecutor`/`SynchronousQueue`, Spring `@Component`/`@Value`, Jakarta `@PreDestroy`, `SseEmitter`, JUnit 5, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`, authoritative rank 11 at line 328.

## Scope and invariants

- Active production owner: `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java`; add `main/java/com/example/lms/api/DebugEventsSseRuntime.java` as the dedicated lifecycle owner.
- Focused test owner: add `src/test/java/com/example/lms/api/DebugEventsDiagnosticsControllerSseLifecycleTest.java`; update only the second test in `src/test/java/com/example/lms/config/DiagnosticsSecurityBoundaryContractTest.java`, which currently enshrines the cached-pool/zero-timeout defect. Preserve its authentication assertions.
- Frozen controller SHA-256=`700526FE82FE15D985A31941A8148D33F6CDE34965E1AB9AE32FE030169D5FEB`. Git reports `.M` because of working-tree metadata/line-ending state, but working/index/HEAD Git blob identity is exactly `fc4965a2b2af41581668f2684380dc4ffd41f9a6`; no textual preexisting hunk exists. Freeze both SHA/blob again before every production patch.
- Frozen diagnostics boundary test SHA-256=`6503FCD63A9130692AD63C4107B95F19DF788470A414F710B28B756FF4BE8A5B`; it is an untracked user file. Make a localized change only to the defect-enshrining wire-contract assertions.
- Retain the four-parameter public HTTP method, URL, event names, initial/tail order, cursor/resume behavior, parameter clamps, redacted fixed-stage fail-soft breadcrumbs, and ADMIN security boundary.
- Runtime `maxClients` defaults to 8 and normalizes to `1..64`. It owns a `ThreadPoolExecutor` with core 0, maximum `maxClients`, finite idle keep-alive, `SynchronousQueue`, daemon named threads, and `AbortPolicy`; no stream task can wait in a queue.
- Runtime is a Spring component but not an `ExecutorService` bean. `@PreDestroy` calls `shutdownNow`; test-only package-private observations may expose shutdown/termination state but no HTTP control surface is added.
- Emitter timeout defaults to 300,000 ms. A nonpositive configured value falls back to that default; all values clamp to `1,000..3,600,000` ms, so configuration cannot restore an infinite lifetime.
- Each accepted stream owns exactly one caller-created `FutureTask<Void>`. Submit with runtime `execute(task)` and retain that same handle; never use a returned/shielded `submit` future and never reuse search/LLM executors.
- The public production constructor is explicitly `@Autowired`; its timeout argument is explicitly `@Value("${lms.debug.events.sse.timeout-ms:300000}")`. It delegates to one unannotated package-private factory-injection constructor, so Spring construction remains unambiguous in web and non-web verification.
- `StreamSession` owns an open flag, terminal-emitter flag, and one task reference. Callback registration precedes task attachment; `attach(task)` cancels and returns false if a callback already closed the session. A false attachment result skips runtime execution entirely. Completion, timeout, and error share one CAS cancellation path, so callback order cannot double-cancel or double-complete.
- Worker send failure or interruption marks the session stopped; worker `finally` completes once only if a servlet callback/rejection has not already made the emitter terminal. The interrupted-sleep path restores the worker interrupt before return.
- Rejection cancels the never-started task and calls `completeWithError` once with fixed code `debug_events_sse_capacity`; it logs/traces no `Last-Event-ID`, event content, exception message/body, session, or request identifier.
- No active-session registry is required: `SynchronousQueue` means accepted cardinality equals active runtime tasks, callback cancellation releases a worker, and `shutdownNow` interrupts every accepted task. Do not add a second scheduler, registry sweeper, queue, semaphore framework, or endpoint.
- Do not modify the bounded `DebugEventStore` from rank 10, other SSE controllers, authentication policy, browser template, or synchronous NDJSON behavior.

---

### Task 1: Refresh gates and freeze the active SSE chain

- [x] **Step 1: Reconfirm root, overlap, and baseline**

  Verify Java 17, branch/HEAD, active source sets, target hashes/blob identity/tracking, index lock, worktrees containing the controller, PatchDrop inventory, source-edit leases, and relevant focused baseline tests. HOLD only this lane on a distinct live writer/lease, changed content blob, index lock, or ambiguous PatchDrop owner.

- [x] **Step 2: Freeze reachability and lifecycle counterexamples**

  Record the admin-protected endpoint, live EventSource caller, static cached executor, zero emitter timeout, long-lived per-stream task, callback-only flag mutation, poll/heartbeat maximum detection delays, send-failure/finally behavior, and lack of Spring shutdown. Record that payload limit 500 bounds neither clients, threads, nor duration.

- [x] **Step 3: Run the repository three-way source-edit preflight**

  Freeze at most 20 redacted evidence rows for scenario `S_DEBUG_EVENTS_SSE_LIFECYCLE`. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`; repeat neutral adjudication in A-B/B-A order. Require stable `APPLY`, identical decisive evidence, and score at least 50. Negative must challenge cancellation-handle shielding, callback-before-attach races, self-cancel/double completion, rejection after emitter creation, zero-queue semantics, executor shutdown ownership, test timing, and scope bleed into other SSE/security/rank10/rank21.

- [x] **Step 4: Acquire the Desktop source-edit lease**

  Acquire topic `priority-100-cohort-c6-debug-events-sse-lifecycle`, freeze declared preimages again before every hunk, and release the lease in `finally`.

---

### Task 2: Add focused RED contracts

**Files:** `src/test/java/com/example/lms/config/DiagnosticsSecurityBoundaryContractTest.java`, `src/test/java/com/example/lms/api/DebugEventsDiagnosticsControllerSseLifecycleTest.java`

- [x] **Step 1: Replace the defect-enshrining source contract and run RED**

  Preserve the ADMIN assertions and established `.name("debug-event")`/no-`view`/no-failure-signal assertions. Replace expectations for `Executors.newCachedThreadPool` and `new SseEmitter(0L)` with negative assertions, and require a Spring-owned runtime with `SynchronousQueue`, finite timeout normalization, `FutureTask` via `execute`, `@PreDestroy`, and `shutdownNow`. Run this single fixture first; current source must fail only the new bounded-lifecycle expectations.

- [x] **Step 2: Add finite-timeout, Spring-construction, and prompt-cancellation tests**

  Use a package-private controller constructor with an injected `LongFunction<SseEmitter>` factory and a test-controlled emitter subclass. Require the public production constructor to carry `@Autowired` and its timeout argument to carry the exact `@Value` default. Use a runtime test thread factory that signals worker start/exit and records interruption without sleeps. Require the configured timeout passed to the emitter to be finite/exact after normalization. Start a stream with maximum poll delay, fire completion after hello, and require the worker to be interrupted and exit within a bounded latch wait rather than waiting five seconds.

- [x] **Step 3: Add idempotence and saturation tests**

  Add a controlled emitter that fires completion during callback registration, before task attachment; require the task to be cancelled, runtime execute count/thread starts to remain zero, and the returned emitter to stay terminal. Fire timeout/error/completion in varying order and require one owned task cancellation/exit with no duplicate explicit completion/error. With runtime capacity one, hold the first accepted stream, open a second, and require immediate fixed-code overload completion, one runtime thread, and no queued task. After closing the first and observing exit, require a subsequent stream to be accepted.

- [x] **Step 4: Add shutdown and send-failure capacity-recovery tests**

  Start an accepted stream, invoke the Spring-runtime shutdown method, and require prompt task interruption/exit, emitter completion, and executor shutdown/termination. Force a controlled emitter send to throw `IOException`; require the worker to exit and a later stream to be admitted. All test runtimes must be shut down in `finally`; no `Thread.sleep`, real browser, network, or servlet container is allowed.

- [x] **Step 5: Run focused RED**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.config.DiagnosticsSecurityBoundaryContractTest" `
    --tests "com.example.lms.api.DebugEventsDiagnosticsControllerSseLifecycleTest" `
    --tests "com.example.lms.api.DebugEventsDiagnosticsControllerFailureSignalTest" `
    --no-daemon `
    --project-cache-dir "C:\AbandonWare\gradle-project-cache\codex-priority100-rank11"
  ```

  Expected: the changed source contract and new lifecycle tests fail/compile-fail only on the absent bounded runtime/session seams; the existing failure-signal contract remains green.

---

### Task 3: Implement the bounded SSE runtime and session lifecycle

**Files:** `main/java/com/example/lms/api/DebugEventsSseRuntime.java`, `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java`

- [x] **Step 1: Add the Spring-owned zero-queue runtime**

  Implement the component with normalized `maxClients`, the approved thread-pool topology, package-private `execute`, and `@PreDestroy shutdownNow`. Add a package-private constructor accepting a test thread factory plus bounded shutdown/termination observations. The class owns no event/session data and emits no raw diagnostics.

- [x] **Step 2: Inject finite emitter construction**

  Remove the static executor/imports. Mark the public production constructor `@Autowired`, inject `DebugEventsSseRuntime`, and annotate its timeout parameter with `@Value("${lms.debug.events.sse.timeout-ms:300000}")`; delegate to an unannotated package-private constructor accepting `LongFunction<SseEmitter>`. Normalize once and create each emitter with that finite value. Keep the public stream method signature exactly four parameters.

- [x] **Step 3: Retain one real task-owned cancellation handle**

  Create/register `StreamSession`, then one `FutureTask<Void>` for the existing cursor/backlog/tail loop. `attach(task)` returns whether the session is still eligible for execution; call `runtime.execute(task)` only on true. A pre-terminal false result cancels the task and performs zero runtime submissions/thread starts. If termination races after a successful attach, the shared callback path cancels the same task. On rejection, use the session's fixed-code terminal path. Refactor `running` reads/writes to session methods without changing cursor, send ordering, heartbeats, clamps, or fail-soft stage labels.

- [x] **Step 4: Make every terminal path idempotent and prompt**

  Completion/timeout/error callbacks mark the emitter terminal and cancel the task once. Callback-before-attach is remembered and cancels on attach. Send failure/interruption stops the worker; `finally` performs at most one explicit completion when the emitter was not already terminal. Never cancel from the worker's normal completion path, and never hold a lock across store access, sends, sleep, logging, or emitter completion.

- [x] **Step 5: Run focused GREEN and inspect invariants**

  Re-run the focused command. Require all lifecycle/source/failure-signal fixtures green. Inspect static counts: cached pool=0, zero-timeout emitter=0, runtime `SynchronousQueue`=1, `shutdownNow` lifecycle=1, controller-owned `FutureTask`=1, `submit`=0, fixed overload reason only, and no raw request/event logging.

---

### Task 4: Review, verify, and record rank 11

- [x] **Step 1: Run affected boundaries**

  Run the new lifecycle fixture, `DiagnosticsSecurityBoundaryContractTest`, `DebugEventsDiagnosticsControllerFailureSignalTest`, the debug diagnostics/redaction/controller fixtures, `ApiExceptionResponseRedactionContractTest`, and admin-token/security boundary tests. Record exact suite/test/failure/error/skipped totals.

- [x] **Step 2: Request independent implementation review**

  Supply approved plan hash, frozen pre/postimages, exact C6 journal/diff, RED/GREEN output, test-thread/emitter topology, static ownership/cap/timeout/cancel/shutdown counts, and privacy scan. Require `SPEC`, `QUALITY`, Critical/Important findings, and `APPROVE|HOLD|REJECT`; fix any Critical/Important finding from a focused RED.

- [x] **Step 3: Broaden verification proportionally**

  Run Desktop-isolated source/version/root/`:app` compile gates, host-isolated full `test --rerun-tasks --fail-fast`, and fresh `bootJar`. Browser proof is optional and not required for the deterministic Java lifecycle contract; do not start a server solely for decorative evidence.

- [x] **Step 4: Integrity and terminal disposition**

  Run exact-target `git diff --check`, hashes/blob status, and count-only secret scan. Record rank 11 `FIXED` only after finite-timeout, prompt-cancel, saturation, capacity-recovery, shutdown, approved review, broad test, and JAR-entry proof. Release the lease in `finally`; do not claim other SSE endpoints or rank 21 fixed.
