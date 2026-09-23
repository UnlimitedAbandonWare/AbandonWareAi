# Priority-100 Cohort C3 Hybrid Web Search Interrupt Preservation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`. Repository policy keeps all writes, integration, verification claims, and final judgment in the parent Codex session; subagents remain bounded read-only analysts/reviewers.

**Goal:** Close authoritative rank 8 by preserving request-thread interruption across every active `HybridWebSearchProvider` await/remerge path and its enabled-by-default AOP boundary, while retaining existing fail-soft fallback results, redacted diagnostics, and `cancel(false)` worker policy and preventing any optional provider/cache join from starting after cancellation is observed.

**Architecture:** Keep the active provider and aspect; introduce no new executor, dependency, exception hierarchy, or global cancellation framework. Every caught `InterruptedException` restores the current request thread with `Thread.currentThread().interrupt()` before returning the existing method-appropriate fallback. Shared generic await helpers retain their return types, so each production call site must check the restored flag immediately after the helper returns and exit its containing search branch before any later provider submission, join, retry, backup query, or cache-only remerge. Direct early/late join catches restore and return immediately. The remerge sleep restores and breaks directly to its existing end telemetry without a cache lookup. The aspect changes from clear-and-continue to observe-and-preserve: a pre-interrupted request records fixed cancellation evidence, never calls `proceed()`, returns the existing empty-list fail-soft value, and leaves the flag set; an interrupt-like thrown failure is converted to the same fallback only after restoring the flag; the `finally` block observes but never clears a residual flag. `Future.cancel(false)` remains unchanged because caller cancellation and worker interruption are separate policies.

**Tech Stack:** Java 17 `Future`/interrupt semantics, Spring AOP, JUnit 5, Mockito, AssertJ, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`, authoritative rank 8.

## Scope and constraints

- Active production owners are `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java` and `main/java/ai/abandonware/nova/orch/aop/HybridWebSearchInterruptHygieneAspect.java`. The initially named `main/java/com/example/lms/service/HybridWebSearchProvider.java` does not exist.
- Active focused tests are `src/test/java/com/example/lms/search/provider/HybridWebSearchProviderAwaitEventRedactionTest.java` and `src/test/java/ai/abandonware/nova/orch/aop/HybridWebSearchInterruptHygieneAspectTest.java`; use existing provider mocks/reflection and `ProceedingJoinPoint` mocks rather than adding infrastructure.
- Frozen planning hashes are provider `2EC493811C0C4BEE6B16CE48468956D37ED7B1445A802E92B16F3356E69C3138`, aspect `B95A1BC7D32A247EE61B68105E12D574DF76A7063B4D54F81271F5389001CDED`, provider test `802A1E2AC3F6AA7C7F153160B4A90B3F1E136323DF55BCDF68AF0E52A24C9942`, and aspect test `C6ED038F0A998BBA1CABB6BB4A85370301C6D162D815DE840D9521932FBDC8AD`. All four files are tracked and already user-dirty; freeze again immediately before every hunk and preserve unrelated changes.
- Rank 8 covers caller/request interrupt preservation only. It does not change rank 9 Self-Ask cancellation, provider credentials, timeout budgets, query construction, result ranking, caches, breaker policy, or executor ownership.
- Keep every existing `future.cancel(false)` call. Do not use `cancel(true)`, `ProcessHandle`, thread stop, executor shutdown, or thread-local clearing.
- No production code in either target may call `Thread.interrupted()` after this repair. `Thread.currentThread().isInterrupted()` remains a legitimate non-clearing observation; tests may call `Thread.interrupted()` only in `finally` cleanup to avoid poisoning the JUnit worker.
- A pre-interrupted request must not invoke `ProceedingJoinPoint.proceed()` or begin provider work. A newly interrupted await returns the current branch's established fail-soft fallback with its flag still set.
- Because the aspect wraps only `search(..)`, `searchWithTrace(..)` must enforce the same pre-interrupted-entry contract itself: observe the flag before bounded-fallback delegation, privacy checks, provider/cache access, or executor submission; record only fixed/redacted preservation evidence; and return `new NaverSearchService.SearchResult(Collections.emptyList(), null)` without clearing the flag. Do not widen the list-valued aspect pointcut over this typed result.
- Once a helper/direct join restores interruption, the containing branch must not submit or wait for another provider, execute backup search, or enter remerge/cache rescue. Returning a partial value already computed before interruption is allowed only where it avoids all later optional work and matches the method's existing result type; otherwise use its established empty fallback.
- Preserve existing redacted cause/status labels. New evidence uses fixed labels, booleans, and counts only; no raw query, result, session, command, header, provider response, or exception text.
- Every interrupt-setting test must use `try/finally { Thread.interrupted(); }`.
- Do not stage, commit, push, merge, deploy, mutate databases/credentials, clean worktrees, add dependencies, or touch any live provider/runtime process.

## Enumerated production surface

Provider interrupt clears to remove and behaviorally cover:

1. `safeGetNow` completed-future collection near line 875.
2. `awaitWithDeadline` hard await near line 1530, including both cancel-suppressed-floor and `cancel(false)` branches.
3. `awaitSoft` near line 1757.
4. Korean Brave-first early hedge near line 1861.
5. Korean Naver late join near line 1960.
6. Korean Naver-first early hedge near line 2141.
7. Korean Naver-first late join near line 2311.
8. Trace Brave-first early hedge near line 2826.
9. Trace Korean Naver-first early hedge near line 3089.
10. Trace Korean Naver-first late join near line 3203.
11. Cache-only remerge retry sleep near line 3629.

Aspect clears to remove and behaviorally cover:

1. Pre-interrupted entry near lines 59-60.
2. Interrupt-like thrown failure near line 77.
3. Residual exit interruption near lines 100-101.

---

### Task 0: Freeze ownership and pass the application-source gate

- [x] **Step 1: Refresh repository gates**

  Record Java 17, branch/HEAD, worktrees, exact four-target status/hashes, index lock, top-level PatchDrop state, active source-edit leases, and only target-relevant overlap/writer evidence. Run Desktop-isolated `checkSourceSetHygiene` and `checkLangchain4jVersionPurity`. A dirty tree alone does not block; a changed frozen target, live overlapping writer/lease, index lock, or ambiguous top-level patch does.

- [x] **Step 2: Freeze active paths and call sites**

  Confirm `search(..)` is wrapped by the enabled-by-default `HybridWebSearchInterruptHygieneAspect`, `searchWithTrace(..)` is not covered by that pointcut, all 11 provider clears and three aspect clears remain reachable, and no active duplicate owner supersedes these paths. Record all `awaitWithDeadline`, `awaitSoft`, and `safeGetNow` call sites that require an immediate post-helper cancellation exit.

- [x] **Step 3: Freeze and adjudicate the required three-way packets**

  Freeze at most 20 redacted evidence rows for scenario `S_HYBRID_WEB_SEARCH_INTERRUPT_PRESERVATION`. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`; repeat neutral adjudication with support packets in A-B and B-A order. Require stable `APPLY`, identical decisive evidence, and score at least 50. The negative packet must challenge whether pooled-thread hygiene justifies clearing, whether restoring alone still permits later provider work, and whether the aspect makes provider changes redundant.

- [x] **Step 4: Acquire the Desktop source-edit lease**

  Use topic `priority-100-cohort-c3-hybrid-web-search-interrupt-preservation` and owner `codex-root-priority-100-c3`. Re-hash all four targets after acquisition and immediately before the first test/source hunk.

---

### Task 1: Prove cancellation loss with focused RED tests

**Files:** `src/test/java/com/example/lms/search/provider/HybridWebSearchProviderAwaitEventRedactionTest.java`, `src/test/java/ai/abandonware/nova/orch/aop/HybridWebSearchInterruptHygieneAspectTest.java`

- [x] **Step 1: Add helper-level provider RED cases**

  Using existing reflection seams and deterministic `Future` fakes whose `get()` or timed `get()` throws `InterruptedException`, prove:

  1. `safeGetNow` returns its exact fallback, retains its existing `interrupted` await event, and leaves the current thread interrupted.
  2. `awaitWithDeadline` returns its exact fallback, preserves both floor/cancel-suppression diagnostics where applicable, leaves the current thread interrupted, and calls `cancel(false)` rather than `cancel(true)` when the current branch requires cancellation.
  3. `awaitSoft` returns its exact fallback, records the existing soft-interrupted event, and leaves the flag set.

  Every case clears the test thread only in `finally`.

- [x] **Step 2: Add boundary and continuation RED cases**

  Add aspect tests proving:

  1. A pre-interrupted request returns the established empty-list fallback, never invokes `proceed()`, records fixed `preserved.entry` evidence, and remains interrupted.
  2. An interrupt-like exception from `proceed()` is converted to the empty fallback after restoring the flag; its raw fixture message is absent from trace values.
  3. A normal `proceed()` that leaves the thread interrupted returns its value but the aspect observes/preserves the flag instead of clearing it.

  Add one deterministic provider branch test through an existing fake executor/provider seam proving that an early interrupted hedge does not submit or call the later optional provider. Add a source contract asserting zero production `Thread.interrupted()` occurrences across the provider and aspect, while allowing test cleanup occurrences.

  Add a direct trace-entry RED case that invokes `searchWithTrace(..)` on a pre-interrupted test thread and proves it returns the typed empty `NaverSearchService.SearchResult`, retains the flag, and performs zero Brave/Naver provider calls, zero cache-only calls, and zero `searchIoExecutor` submissions. Keep the cleanup in `finally { Thread.interrupted(); }`.

- [x] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.search.provider.HybridWebSearchProviderAwaitEventRedactionTest" `
    --tests "ai.abandonware.nova.orch.aop.HybridWebSearchInterruptHygieneAspectTest" `
    --no-daemon `
    --project-cache-dir "C:\AbandonWare\gradle-project-cache\codex-priority100-rank8"
  ```

  Expected: only the new interrupt-preservation/no-continuation assertions fail. Existing redaction, timeout, fallback, and aspect telemetry tests must not regress for unrelated reasons.

---

### Task 2: Implement the smallest full-surface repair

**Files:** `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java`, `main/java/ai/abandonware/nova/orch/aop/HybridWebSearchInterruptHygieneAspect.java`

- [x] **Step 1: Repair shared await helpers without changing worker policy**

  In `safeGetNow`, `awaitWithDeadline`, and `awaitSoft`, replace flag clearing with `Thread.currentThread().interrupt()` immediately after catching `InterruptedException`. Preserve the exact fallback, current redacted await event, floor/cancel-suppression counters, and existing `cancel(false)` behavior. Do not throw a new public exception or change generic method signatures.

- [x] **Step 2: Stop helper callers after restored interruption**

  At every production `safeGetNow`/`awaitWithDeadline`/`awaitSoft` call site, check `Thread.currentThread().isInterrupted()` before any subsequent provider submission, wait, retry, backup, or remerge. Return the containing method's existing empty or already-computed partial fallback immediately. Prove through source inspection that no helper-restored branch can reach another optional provider/cache operation.

  At the very top of `searchWithTrace(..)`, before its bounded-fallback delegation or any provider/cache/executor work, add a non-clearing `isInterrupted()` guard. Record fixed/redacted trace-entry preservation evidence, return `new NaverSearchService.SearchResult(Collections.emptyList(), null)`, and leave the flag set. Keep this guard in the provider because the existing aspect has a `List` fallback contract and does not wrap `searchWithTrace(..)`.

- [x] **Step 3: Repair direct join and remerge catches**

  At all seven direct early/late join catches, restore the flag, retain the current redacted suppression label, and return the method-appropriate empty/partial result immediately. For the remerge sleep catch, restore, retain `web.failsoft.remergeOnce.interrupted=true`, and exit the polling loop without executing a cache-only lookup; allow only its existing count/fixed-label end telemetry to run.

- [x] **Step 4: Convert the aspect to preservation policy**

  Remove entry/catch/exit calls to `Thread.interrupted()`. On pre-interrupted entry, record fixed `web.interruptHygiene.preserved.entry.*` evidence, skip `proceed()`, return `Collections.emptyList()`, and leave the flag set. On interrupt-like thrown failure, call `Thread.currentThread().interrupt()`, retain fail-soft empty output, and record fixed `preserved.throw.*` evidence without raw exception data. In `finally`, observe residual interruption with fixed `preserved.exit.*` evidence but never clear it. Update the class contract/comments so documentation no longer claims clearing is intentional.

  Existing `EvidenceListTraceInjectionAspect` consumers of `web.interruptHygiene.cleared.*` retain their literal old/false semantics; do not repurpose those keys as aliases for preservation. Rank 8 guarantees the new fixed `preserved.*` values in `TraceStore` only. Browser/UI projection of those new keys is explicitly outside this cohort and requires a separate approved change if later demanded.

- [x] **Step 5: Run focused GREEN and inspect invariants**

  Re-run both focused fixtures. Inspect XML totals, exact four-target diff, postimage hashes, every provider/aspect interrupt read/write, all helper call sites, and all `cancel(...)` calls. Required static invariants: production `Thread.interrupted()` count 0; `cancel(true)` count 0; existing `cancel(false)` sites unchanged except contextual line movement.

---

### Task 3: Review, verify, and record rank 8

- [x] **Step 1: Run affected boundaries**

  Locate and run active `HybridWebSearchProvider*Test`, `HybridWebSearchInterruptHygieneAspectTest`, `NovaAutoConfigurationSourceContractTest`, `AspectOrderingContractTest`, and search fail-soft/redaction contract fixtures. Use `NovaAutoConfigurationSourceContractTest` only to prove that the `HybridWebSearchInterruptHygieneAspect` bean remains registered; separately record a source-inspection proof that `NovaOrchestrationAutoConfiguration.java` still applies the exact `@ConditionalOnProperty(name = "nova.orch.interrupt-hygiene.enabled", havingValue = "true", matchIfMissing = true)` annotation. Include the direct `searchWithTrace(..)` pre-interrupted-entry RED/GREEN fixture. Record exact suite/test/failure/error/skipped totals. No real provider or credential call is allowed.

- [x] **Step 2: Request independent implementation review**

  The reviewer receives the approved plan hash, frozen preimages, exact C3-only change journal or diff, RED/GREEN evidence, postimage hashes, static interrupt/cancel counts, and privacy scan. Require `SPEC`, `QUALITY`, Critical/Important findings, and `APPROVE|HOLD|REJECT`. Any Critical or Important finding gets its own focused RED before correction.

- [x] **Step 3: Broaden verification proportionally**

  Run Desktop-isolated `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes`, then a host-isolated full `test --rerun-tasks --fail-fast` and fresh `bootJar`. Browser/provider-wire proof is not required for a deterministic request-thread interruption contract and must not be substituted for unit evidence.

- [x] **Step 4: Integrity and terminal disposition**

  Run exact-target `git diff --check`, postimage hashes, and a count-only secret scan. Record rank 8 as `FIXED` only after approved review and fresh focused/broad proof. Release the source-edit lease in `finally`.
