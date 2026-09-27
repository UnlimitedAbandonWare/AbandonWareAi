# Priority-100 Cohort C2 Local LLM Owned-Process Lifecycle Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`. Repository policy keeps all writes, integration, live-process decisions, verification claims, and final judgment in the parent Codex session; subagents remain bounded read-only analysts/reviewers.

**Goal:** Close authoritative rank 7 by guaranteeing that `LocalLlmProcessManager` makes a bounded termination attempt against only the exact root and descendant handles captured from the process it actually launched, both on Spring lifecycle shutdown and on a post-launch startup failure, while never terminating an already-running/reused Ollama listener or claiming authority over detached/unobserved descendants.

**Architecture:** Preserve the existing `LocalLlmProcessManager` and `StartupRuntime` seam. Replace the PID-only launch result with one narrow owned-process capability created directly from the `Process` returned by this manager's `ProcessBuilder`. The manager stores that capability only after its own successful launch. Lifecycle stop and post-launch failure atomically claim the stored capability and request a bounded graceful-then-forced termination. A narrow process-tree operations seam captures exact `ProcessHandle` wrappers from the launched root and its reachable descendants, refreshes once before the forced pass, and operates on the union of those exact handles; it never resolves or kills a process by PID or port. Detached or later-unobserved descendants are not guessed, and outcomes describe only captured-handle termination or explicit `still_alive`/`tree_scope_incomplete` states. Reused/foreign listeners produce no owned capability, so shutdown is a no-op for them. Fixed-label, count/boolean-only trace evidence records the outcome without commands, environment values, paths, model names, or provider data.

**Tech Stack:** Java 17 `Process`/`ProcessHandle`, Spring `SmartLifecycle`, `AtomicReference`, JUnit 5, AssertJ, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`

## Scope and constraints

- Active owners are `main/java/com/example/lms/config/LocalLlmProcessManager.java` and `src/test/java/com/example/lms/config/LocalLlmProcessManagerTest.java`.
- This plan covers rank 7 only. It does not change local model names, endpoints, GPU selection, embedding dimensions, provider routing, warmup policy, autostart defaults, process discovery, port ownership, or ranks 8/9 cancellation behavior.
- Both target files are heavily user-dirty. Frozen preimages are `800367CE8BDA10AC6287C06B6A8192D7693568D67BFA5B7076821E14CD071FA1` for production and `5869130DDDE281501631EC6FAA8144FCCD9D6180B29A06FF98D50D0B192D4B64` for the test. Re-hash immediately before every hunk and preserve all unrelated lines.
- A separate worktree, `C:\AbandonWare\worktrees\codex-ollama-owned-child-rollback-20260824`, is dirty on the same two targets from the same base HEAD. Its 83-production-line/100-test-line owned-child delta is a read-only semantic candidate, not mutation authority and not a patch to copy. Rank 7 source mutation remains lane-local `HOLD` until Task 0 proves no active writer, designates the Desktop canonical root as the final owner, and reconciles that candidate's useful contracts without changing the other worktree.
- Fresh read-only runtime evidence found Java 17, the `ollama` command, and healthy loopback `/api/tags` responses on ports 11434 and 11435. Those processes are unrelated live owners and must not be stopped, restarted, signaled, or used as lifecycle-test targets.
- A `ProbeResult` PID is observational only. Neither `launchPid`, a health-probe PID, a port PID, `ProcessHandle.of(pid)`, Taskkill, Stop-Process, nor any other PID/port lookup may become a termination authority.
- The only termination authority is the capability built around the exact `Process` returned by this manager's own `ProcessBuilder.start()` call.
- A default direct `ollama serve` launch and an explicit shell-backed launch are both owned. The system capability captures the launched root plus its reachable descendants before the graceful pass, refreshes once before the forced pass, and operates on the union of those exact handles. A detached process or a descendant created after the final capture must not be guessed or killed; the result must never claim complete-tree termination beyond the captured handles.
- Termination is synchronous and bounded: a small fixed graceful budget followed by a smaller fixed forced budget. No new configuration property or dependency is introduced.
- `stop()` is fail-soft and always leaves `running=false`. `stop(Runnable)` invokes the callback exactly once after the local cleanup attempt, even when the owned process already exited or termination reports failure.
- A termination result that still reports a live owned handle is retained for a later bounded retry rather than silently discarded. An already-exited or terminated handle is cleared.
- Startup failure after a managed launch invokes the same owned-process cleanup before returning or throwing. Failures before launch and failures while reusing an external listener have no process cleanup authority.
- If the shutdown thread is interrupted, the system adapter restores the interrupt flag, performs no unbounded wait or forced wait in that invocation, and returns a fixed interrupted/still-alive outcome. It must not clear the interrupt for downstream work.
- New lifecycle shutdown/termination trace and log fields may contain only fixed reason/status labels, booleans, elapsed/timeout counts, captured-handle counts, and `pidPresent`. Existing startup diagnostics are out of scope and remain behaviorally unchanged. New lifecycle evidence must not contain the command, environment, URL, host, path, model, CUDA UUID, process name, raw PID, or exception message.
- Tests use only fake owned-process capabilities. They must not launch, stop, or probe a real OS process and must not depend on either live Ollama endpoint.
- Do not stage, commit, push, merge, deploy, mutate a live database, delete files, add dependencies, clean worktrees, or terminate any pre-existing process.

## File structure

### Production file

- Modify `main/java/com/example/lms/config/LocalLlmProcessManager.java`: owned-process abstraction/result, atomic ownership retention, bounded lifecycle/failure cleanup, system process-tree adapter, and redacted shutdown evidence.

### Test file

- Modify `src/test/java/com/example/lms/config/LocalLlmProcessManagerTest.java`: managed/reused/failure/callback/idempotency/retry lifecycle contracts through the existing fake runtime.

---

### Task 0: Freeze ownership and pass the application-source gate

- [x] **Step 1: Refresh repository gates**

  Record Java/Gradle versions, branch, HEAD, worktrees, exact target status/hashes, index lock, top-level PatchDrop state, current source-edit leases, and read-only loopback process/port ownership. Re-run `checkSourceSetHygiene` and `checkLangchain4jVersionPurity` with Desktop split outputs. Do not probe or mutate provider credentials.

- [x] **Step 2: Freeze the active failure path and mutation points**

  Confirm `postProcessBeanFactory -> start -> StartupRuntime.launch`, `handleStartupFailure`, `stop`, `stop(Runnable)`, `LaunchResult`, and `SystemStartupRuntime.launch` are the only required canonical seams. Read the overlapping worktree's exact target delta and evidence artifacts without modifying them; identify which contracts are sound, which violate this plan (including real-process tests or PID authority), whether any writer/lease/process still owns that worktree, and why the canonical Desktop root can or cannot become the sole final owner. Confirm existing reused-listener behavior and all unrelated CUDA, warmup, startup-redaction, and health-polling hunks remain untouched.

- [x] **Step 3: Freeze and adjudicate the required three-way packets**

  Freeze at most 20 redacted evidence rows for scenario `S_LOCAL_LLM_OWNED_PROCESS_LIFECYCLE`. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`; repeat neutral adjudication with support packets in A-B and B-A order. Require stable `APPLY`, identical decisive evidence, and score at least 50.

- [x] **Step 4: Acquire the Desktop source-edit lease**

  Use topic `priority-100-cohort-c2-local-llm-owned-process-lifecycle` and owner `codex-root-priority-100-c2`. Re-hash both targets after acquisition and immediately before the first source hunk. HOLD rank 7 only on changed preimage, target writer/lease conflict, index lock, active top-level PatchDrop ambiguity, or failed neutral adjudication.

---

### Task 1: Prove the lifecycle ownership gap with focused RED tests

**File:** `src/test/java/com/example/lms/config/LocalLlmProcessManagerTest.java`

- [x] **Step 1: Extend the existing fake runtime without replacing it**

  Add a fake owned-process capability that records termination calls and fixed graceful/forced budgets, exposes a configurable terminal result, and appends deterministic event labels. Keep the existing fake probe, clock, launch-request, GPU, and sleep behavior intact.

- [x] **Step 2: Add deterministic RED cases**

  Add tests proving:

  1. A healthy process launched by this manager is retained as owned and receives exactly one bounded terminate request during `stop()`; the manager is no longer running and fixed-label shutdown evidence reports owned termination.
  2. A healthy listener found before launch is reused, receives no owned handle and no termination call during `stop()`, even though its observational probe PID is nonzero.
  3. A startup health timeout after a successful managed launch terminates the owned process before fail-fast propagates the classified startup exception.
  4. `stop(Runnable)` invokes its callback exactly once after the owned cleanup event, including when the process reports already exited.
  5. Repeated `stop()` calls do not terminate an already-cleared owned capability twice.
  6. If the first termination result reports `still_alive`, the exact owned capability remains available and a later `stop()` retries it; no lookup by its PID occurs.
  7. Shutdown trace/log payloads contain no fixture command, environment value, host, path, model, CUDA UUID, process name, raw PID, or synthetic exception message.
  8. When the calling shutdown thread is interrupted before or during bounded termination, the fake owned capability reports `interrupted_still_alive`; `stop()` returns with `running=false`, the current thread's interrupt flag is restored, no forced wait/retry occurs in that invocation, the same exact capability remains retained for a later retry, and a non-null callback still executes exactly once. The test uses `finally { Thread.interrupted(); }` so the JUnit worker cannot remain poisoned.
  9. A managed-launch warmup failure terminates the exact owned capability before propagating fail-fast, while the same warmup failure after a reused healthy listener performs no termination.
  10. A null callback is guarded defensively after cleanup; a non-null callback remains exactly-once. A deterministic compare-and-set race fixture proves that a stale `still_alive` result cannot restore capability A over a different reference installed during the cleanup attempt.

  Tests must use fake capabilities/handle graphs and loopback test servers only, contain no sleeps, and leave the two live Ollama processes untouched.

- [x] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.config.LocalLlmProcessManagerTest" --no-daemon --project-cache-dir "C:\AbandonWare\gradle-project-cache\codex-priority100-rank7"
  ```

  Expected: compilation/assertion failures only for the missing owned-process result/capability, termination invocation, startup-failure cleanup, and shutdown evidence. Existing 26 baseline tests must not regress for an unrelated reason.

---

### Task 2: Add the smallest owned-process lifecycle repair

**File:** `main/java/com/example/lms/config/LocalLlmProcessManager.java`

- [x] **Step 1: Add an exact owned-process capability**

  Add one narrow package-visible `OwnedProcess` interface with `pid()` and a bounded `terminate(gracefulMs, forcedMs)` operation, plus a fixed-label `TerminationResult`. Change `LaunchResult` to carry a non-null owned capability and derive its observational PID from that capability. Add an `AtomicReference<OwnedProcess>` to the manager; do not add any PID-based fallback. Add a package-visible process-tree operations seam whose API accepts only exact wrapped root/descendant handles, monotonic time, and bounded waits; it has no lookup-by-PID method.

- [x] **Step 2: Retain ownership only on this manager's launch**

  In the existing managed-launch block, store the capability returned by `StartupRuntime.launch` and retain the current redacted launch evidence. Reused, disabled, occupied-listener, and pre-launch-failure branches leave the ownership reference empty. A null/malformed launch capability is classified as `start_command_failed` without inventing ownership.

- [x] **Step 3: Centralize bounded cleanup**

  Add one cleanup helper used by both lifecycle stop and `handleStartupFailure`. Atomically claim the current capability, call its bounded terminate operation outside any synchronized block, and record only fixed-label/boolean/count evidence. Clear a terminated/already-exited capability. If the result is `still_alive` or `interrupted_still_alive`, restore the same exact capability only with `compareAndSet(null, claimed)`; it must never overwrite a different reference installed during cleanup. Cleanup exceptions are reduced to a fixed `termination_error` result and never expose raw messages.

  `stop()` first makes `running=false`, then invokes the helper. `stop(Runnable)` uses `try/finally`; it defensively ignores a null callback and runs a non-null callback exactly once after cleanup. `handleStartupFailure` performs cleanup before its existing fail-fast decision while retaining the classified startup reason and launch diagnostics. This common path covers health timeout and managed-launch warmup failure; reused-listener warmup failure owns no capability and performs no termination.

- [x] **Step 4: Implement the system process-tree adapter**

  Wrap the exact `Process` returned by `ProcessBuilder.start()`. At termination time capture the root handle and its reachable descendants as exact handles, request graceful destruction of descendants and root, and poll only that captured finite set until the graceful deadline. Immediately before the forced pass, refresh descendants from the exact root and add them to the captured-handle union. If any captured handles remain alive, request forced destruction only on them and poll until the forced deadline. Report `captured_handles_terminated`, `already_exited`, `still_alive`, `interrupted_still_alive`, or `tree_scope_incomplete`; never report an unobservable complete-tree guarantee. Use monotonic time and bounded short waits. On interrupt, restore the flag immediately and return without an unbounded or forced wait. Never call `ProcessHandle.of(pid)` or a shell kill command.

  Inside `SystemStartupRuntime.launch`, once `ProcessBuilder.start()` returns, any failure while constructing the owned capability or `LaunchResult` must best-effort terminate only that exact returned process and the handles captured from it with the same bounded helper before rethrowing. Add an injectable process starter/capability factory only as narrowly as required to test this ownership-transfer rollback without launching a real OS process.

- [x] **Step 5: Prove the system adapter with a fake handle graph**

  Use the package-visible process-tree operations seam to test an exact fake root/descendant graph. Prove root-plus-descendant membership, descendant-before-root graceful signalling, one refresh before force, union retention, graceful-before-forced ordering, bounded waits, interruption outcome, and absence of any PID/port lookup API or shell command. Add an injected launch-transfer failure test whose synthetic starter returns one fake exact process, whose capability factory fails, and whose rollback cleanup is invoked exactly once on that process only.

- [x] **Step 6: Run focused GREEN and inspect ownership invariants**

  Re-run `LocalLlmProcessManagerTest`. Inspect XML totals, exact target diff, postimage hashes, all assignments to the ownership reference, all termination call sites, and all PID/port lookup sites. Confirm the capability can originate only from `SystemStartupRuntime.launch` or the injected test runtime, and that no reused listener can populate it.

---

### Task 3: Review, verify, and record rank 7

- [x] **Step 1: Run affected-boundary tests**

  Run all active `LocalLlmProcessManager*Test` and local LLM desktop profile/source-contract fixtures located by `rg`. Record exact XML suite/test/failure/error/skipped totals. No test may stop the live loopback processes.

- [x] **Step 2: Request independent task and integrated review**

  The reviewer receives the contract, exact diff, RED/GREEN evidence, pre/post hashes, live-process non-interference evidence, and privacy evidence. Require `SPEC`, `QUALITY`, Critical/Important findings, and `APPROVE|HOLD|REJECT`. Any Critical/Important finding gets a focused RED before correction.

- [x] **Step 3: Broaden verification proportionally**

  Run Desktop-isolated `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes`, followed by the host-isolated full `test --rerun-tasks --fail-fast` and `bootJar`. Re-probe the two loopback endpoints and read-only process owners after verification and record only that the pre-existing owners remained observed/reachable with the same observed IDs. This is non-interference evidence, not exclusive-ownership proof and not proof that no unrelated process changed meanwhile.

- [x] **Step 4: Integrity and terminal disposition**

  Run exact-target `git diff --check`, postimage hashes, and a count-only secret scan. Record rank 7 as `FIXED` only after approved review and fresh focused/broad proof. Release the source-edit lease in `finally`.
