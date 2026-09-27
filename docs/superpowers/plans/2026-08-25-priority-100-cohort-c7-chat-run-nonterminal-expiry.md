# Priority-100 Cohort C7: Chat Run Nonterminal Expiry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`. Repository policy keeps all writes, integration, verification claims, and final judgment in the parent Codex session; subagents remain bounded read-only analysts/reviewers.

**Goal:** Close authoritative rank 12 by giving orphaned `RUNNING`, `COMMITTING`, and stalled `CANCELLING` chat runs a bounded idle lifetime, one exact-run timeout terminalization, safe replacement/replay semantics, and Spring-owned scheduler shutdown.

**Architecture:** Keep `chat.resume.ttl-seconds` exclusively as the existing terminal replay TTL. Add a separate normalized `chat.resume.inflight-idle-timeout-seconds` and one `@PostConstruct` fixed-delay stale sweep on the registry's existing single scheduled executor. Inject a package-private monotonic wall-clock seam for deterministic tests. Each run records last lifecycle progress under its existing `run.gate`. The sweep examines current run identities, and only after rechecking both session and token identity under the run gate may it win terminalization: detach the exact cancellation handle/producer sink, emit at most one fixed `stale_timeout` terminal status, complete the replay sink, and schedule the existing terminal replay eviction once. A late cancellation/commit owner loses against the terminal state and cannot affect a replacement. `@PreDestroy` always cancels the registry's sweep and shuts down the executor only when the registry created it.

**Tech Stack:** Java 17 `ScheduledExecutorService`/`ScheduledFuture`/`LongSupplier`, Reactor `Sinks`/`Disposable`, Spring `@Value`/`@PostConstruct`/`@PreDestroy`, JUnit 5, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`, authoritative rank 12 at line 329.

## Scope and invariants

- Active production owner: modify only `main/java/com/example/lms/service/chat/ChatRunRegistry.java` unless focused RED proves another active owner must change.
- Focused test owner: add `src/test/java/com/example/lms/service/chat/ChatRunRegistryNonTerminalExpiryTest.java`. Preserve dirty `ChatRunRegistryEvictionTest`, untracked `ChatStreamEmitterRunIdentityTest`, `ChatRunExecutionContext`, and `ChatApiController` unless a proven contract correction is unavoidable.
- Frozen registry SHA-256=`81EA4E8DF790D38209331BA9FB60100AE70894811BE25F620D2D82F3BF28E916`; tracked/user-dirty with 578 insertions and 45 deletions versus HEAD. Freeze this entire post-user preimage before every production hunk and never reconstruct it from HEAD.
- Existing terminal replay TTL property/meaning remains `chat.resume.ttl-seconds:300`. Add `chat.resume.inflight-idle-timeout-seconds:1800`; nonpositive values fall back to 1,800 seconds and all values clamp to 30..86,400 seconds. No resource file is required.
- One registry-wide stale sweep is started idempotently by `@PostConstruct` on the existing evictor. Its fixed-delay interval is derived from the normalized idle timeout and bounded to 1..30 seconds. No per-run thread, per-run scheduled deadline, global registry lock, new executor, dependency, or endpoint is added.
- The current public no-arg registry owns its executor. Package-private injected-executor constructors remain caller-owned and gain a package-private `LongSupplier` clock seam. `@PreDestroy` cancels the sweep for either ownership mode, but calls `shutdownNow` only for the production-owned executor.
- Replace unused `Instant started/lastEvent` initialization with gate-owned `lastProgressMillis` initialized from the injected clock. Backward clock movement cannot make a run stale: progress time is monotonic-max and idle age clamps at zero.
- Touch progress only for accepted lifecycle progress: successful main/producer emission, acknowledgement, commit transition/claim, persistence/outcome/final-event update, cancellation transition, and accepted cancellation/producer handles. Read-only attach/state calls do not keep an orphan alive.
- Sweep iteration may be weakly consistent, but terminalization requires `runs.get(sessionId) == expectedRun`, `runsByToken.get(runId) == expectedRun`, nonterminal status, and elapsed idle timeout, all rechecked inside `run.gate` before mutation.
- Timeout sets the exact run terminal `CANCELLED`, counts down acknowledgement, detaches its cancellation handle and producer sink, records only fixed bounded reasons (`generationOutcome=timed_out`, `terminalReason=stale_timeout`, `finalDeliveryFailureReason=stale_timeout`), emits at most one fixed terminal status, and completes both sinks. Disposable disposal occurs outside `run.gate` and exactly once.
- Timeout keeps the old token in `runsByToken` for the existing terminal replay TTL. `beginOrJoin` may immediately replace the terminal current pointer; the old conditional terminal eviction must never remove the replacement.
- Add a run-gate `terminalEvictionScheduled` guard so watchdog/cancel/markDone races schedule terminal removal once. A late blocking cancellation action rechecks status, loses without a second event/disposal, and cannot contaminate a replacement.
- Scheduled sweep failures are caught so one bad run cannot permanently stop later sweeps; logs contain only a fixed stage and exception type, never session ID, run token, event data, outcome body, or exception message.
- Preserve one-owner begin/join, exact-token authorization, replay ordering/capacity, normal cancellation blocking semantics before timeout, commit/persistence fencing, current state response, terminal TTL, and all existing event/outcome shapes outside the new fixed timeout reason.
- Do not add a hard terminal-map cap, modify chat HTTP routes/security/UI, alter public request budgets, change provider timeouts, or patch other registries/executors in C7.

---

### Task 1: Refresh gates and freeze the active chat-run chain

- [x] **Step 1: Reconfirm root, overlap, and baseline**

  Verify Java 17, branch/HEAD, active source sets, all named target hashes/tracking/diffs, index lock, worktrees containing the registry, PatchDrop inventory, source-edit leases, and fresh baseline for the registry eviction/identity plus cancel/state/input-guard fixtures. HOLD only this lane on a distinct live writer/lease, changed preimage, index lock, or ambiguous PatchDrop owner.

- [x] **Step 2: Freeze reachability and bounded counterexamples**

  Record `ChatApiController` begin/join, exact attach/state/cancel, normal `markDone`, client-detach behavior, terminal-only schedule calls, the absent nonterminal schedule/sweep, and both deterministic worlds: orphan `RUNNING/COMMITTING` blocks replacement forever; blocked cancellation action strands `CANCELLING` while attach and replacement are both denied.

- [x] **Step 3: Run the repository three-way source-edit preflight**

  Freeze at most 20 redacted evidence rows for `S_CHAT_RUN_NONTERMINAL_EXPIRY`. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`; repeat neutral A-B/B-A. Require stable `APPLY`, identical decisive evidence, and score at least 50. Negative must challenge idle-vs-absolute lifetime, activity touch abuse, sweep death, identity/replacement races, CANCELLING late return, double terminal/disposal/eviction, sink cleanup, clock rollback, executor ownership, privacy, and dirty-hunk preservation.

- [x] **Step 4: Acquire the Desktop source-edit lease**

  Acquire topic `priority-100-cohort-c7-chat-run-nonterminal-expiry`, freeze declared preimages immediately before every hunk, and release in `finally`.

---

### Task 2: Add deterministic RED contracts

**File:** `src/test/java/com/example/lms/service/chat/ChatRunRegistryNonTerminalExpiryTest.java`

- [x] **Step 1: Add a manual clock and scheduled-executor harness**

  Use a package-local mutable `LongSupplier`, a controlled `ScheduledThreadPoolExecutor` that captures the fixed-delay sweep/terminal eviction without sleeping, latch-bounded cancellation workers, and count-only `Disposable`/sink observers. Every real executor is stopped in `finally`; no `Thread.sleep`, network, browser, or Spring context is allowed.

- [x] **Step 2: Prove idle RUNNING and COMMITTING timeout semantics**

  Start a run, advance only the fake clock past the normalized deadline, invoke the production sweep seam, and require exact handle disposal once, fixed timeout event once, completed old replay, retained old exact-token replay before terminal TTL, and immediate fresh owner/token replacement. Repeat after transcript-commit claim and require stale owner persistence/emission/completion to fail without touching R2.

- [x] **Step 3: Prove progress and replacement fencing**

  Advance near the deadline, successfully emit lifecycle progress, cross the original creation deadline but not the idle deadline, and require the run retained. Cross the new idle deadline and require timeout. Execute old timeout/terminal eviction work after R2 exists and require R2 maps/token/sink unchanged. Move the fake clock backward and require no premature expiry.

- [x] **Step 4: Prove stalled CANCELLING and lifecycle ownership**

  Block `beforeTerminal` on a latch after status becomes `CANCELLING`; advance the fake clock and sweep. Require timeout to unblock registry replacement without waiting for the action. After releasing the action, require its late path to lose with one terminal event, one disposal, and one terminal-eviction reservation. Separately prove `@PostConstruct` starts one sweep; destroy cancels it; owned executor shuts down; injected executor remains caller-owned.

- [x] **Step 5: Run focused RED**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.service.chat.ChatRunRegistryNonTerminalExpiryTest" `
    --tests "com.example.lms.service.chat.ChatRunRegistryEvictionTest" `
    --no-daemon `
    --project-cache-dir "C:\AbandonWare\gradle-project-cache\codex-priority100-rank12"
  ```

  Expected: the new fixture compile-fails or fails only on absent idle-timeout/clock/sweep/ownership seams; the existing eviction fixture remains green.

---

### Task 3: Implement exact-run stale terminalization

**File:** `main/java/com/example/lms/service/chat/ChatRunRegistry.java`

- [x] **Step 1: Add clock, timeout, sweep, and ownership seams**

  Add bounded configured idle timeout, injected clock, ownership flag, idempotent `@PostConstruct` fixed-delay scheduling, fixed-type fail-soft sweep wrapper, and `@PreDestroy` cancellation/conditional shutdown. Do not change the public registry API or expose run IDs.

- [x] **Step 2: Track only accepted lifecycle progress**

  Initialize progress at run creation and update it under `run.gate` on the approved successful transitions. Keep read-only attach/state outside the liveness contract. Normalize the clock with monotonic-max writes and nonnegative idle calculation.

- [x] **Step 3: Terminalize one stale exact run atomically**

  Iterate current runs, recheck current/token identity and elapsed idle under the run gate, win only from a nonterminal state, detach owned resources, record fixed timeout outcome, emit/complete once, dispose outside the lock, and call the guarded terminal eviction scheduler. Do not remove the token/current map directly at timeout.

- [x] **Step 4: Make normal terminal races and cleanup idempotent**

  Route `markDone`, successful cancel, and timeout through the one terminal-eviction guard. Ensure the late CANCELLING path and old terminal task are identity-conditional no-ops against R2. Keep scheduler/log/file/network work outside `run.gate` except existing nonblocking sink operations.

- [x] **Step 5: Run focused GREEN and inspect invariants**

  Re-run the focused command. Require new and existing eviction fixtures green. Inspect static invariants: one fixed-delay sweep, no per-run executor/thread, idle timeout distinct from terminal TTL, one owned shutdown, conditional injected ownership, fixed timeout reasons only, exact map identity checks, and no raw identifiers in logs.

---

### Task 4: Review, verify, and record rank 12

- [x] **Step 1: Run affected boundaries**

  Run nonterminal/terminal eviction, stream run identity, cancel, state security, and input-guard fixtures. Record exact suite/test/failure/error/skipped totals.

- [x] **Step 2: Request independent implementation review**

  Supply approved plan hash, frozen pre/postimages, exact C7 journal/diff, RED/GREEN output, fake-clock/manual-scheduler topology, state/identity/terminal/disposal counts, ownership checks, and privacy scan. Require `SPEC`, `QUALITY`, C/I/M findings, and `APPROVE|HOLD|REJECT`; fix any Critical/Important finding from focused RED.

- [x] **Step 3: Broaden verification proportionally**

  Run Desktop-isolated source/version/root/`:app` compile gates, host-isolated full `test --rerun-tasks --fail-fast`, and fresh `bootJar`. Browser proof is optional: no UI or HTTP contract changes are planned, and deterministic registry/controller tests are the acceptance surface.

- [x] **Step 4: Integrity and terminal disposition**

  Run exact-target `git diff --check`, hashes/status, count-only secret scan, and JAR-entry proof for `ChatRunRegistry`. Record rank 12 `FIXED` only after idle timeout, progress deferral, CANCELLING escape, replacement fencing, owned shutdown, approved review, broad test, and executable identity all pass. Release the lease in `finally`.
