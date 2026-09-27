# Priority-100 Cohort C5: Debug Fingerprint Retention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`. Repository policy keeps all writes, integration, verification claims, and final judgment in the parent Codex session; subagents remain bounded read-only analysts/reviewers.

**Goal:** Close authoritative rank 10 by placing a strict, concurrency-correct bound on `DebugEventStore.byFingerprint` while preserving the independent recent-event ring, retained-fingerprint rate-limit/totals semantics, existing diagnostics response shape, fail-soft emission, and redaction contracts.

**Architecture:** Keep the existing `DebugEventStore`; add no cache dependency, scheduler, executor, background sweeper, duplicate store, endpoint, or resource file. Introduce one private aggregate-state mutex, a configurable positive fingerprint cap whose missing/nonpositive value falls back to the positive event-ring cap, and a monotonically increasing touch order recorded in each `AggState`. All aggregate admission, touch, counter update, eviction, and map snapshot iteration occur under the short mutex; event construction, sanitization, ring insertion, JSON serialization, logging, and NDJSON I/O remain outside it. When a new fingerprint arrives at capacity, evict exactly the least-recently-touched state before insertion, using the map key only as a deterministic tie-break and never logging or tracing it. An evicted fingerprint starts a new aggregation lifetime if it returns.

**Tech Stack:** Java 17, Spring `@Value`, existing `ConcurrentHashMap`/synchronized aggregate state, JUnit 5, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`, authoritative rank 10 at line 327.

## Scope and invariants

- Active production owner: `main/java/com/example/lms/debug/DebugEventStore.java`.
- Add one focused test owner: `src/test/java/com/example/lms/debug/DebugEventStoreFingerprintRetentionTest.java`. Retain `DebugEventRedactionTest` and controller/page tests as affected boundaries; do not fold the new concurrency/retention fixture into already user-dirty tests.
- Frozen planning production SHA-256: `1D2938818CD4BB5AC5101E9A38D9E261422EB6AFD1E07DD5B3A62758B5EC73B7`. The file is tracked and user-dirty; its current unrelated suppressed-trace breadcrumbs and `TRACE_MEMORY` enrichment must be preserved byte-for-byte outside localized C5 hunks.
- `byFingerprint.size() <= effectiveMaxFingerprints` must hold whenever the aggregate-state mutex is released, including concurrent distinct-fingerprint emission.
- The effective cap is `maxFingerprints` when positive; otherwise it is `max(1, maxSize)`. Bind `maxFingerprints` from `lms.debug.events.rate.max-fingerprints` with a missing default of `0`, so the established ring maximum remains the default scale without editing an ignored/local resource.
- Event-ring insertion/eviction and `lms.debug.events.max-size` behavior remain unchanged. A fingerprint aggregate may be evicted while its recent event remains in the ring; this separation is intentional.
- Existing retained states keep exact `windowCount`, `suppressedInWindow`, `total`, `totalSuppressed`, `lastMessage`, and `lastError` behavior. Only an evicted/reintroduced fingerprint resets to a new lifetime.
- Update recency on every aggregate event, including suppressed events. Use a store-local monotonically increasing order under the same mutex; tests must not use wall-clock sleeps to establish eviction order.
- Keep the `ConcurrentHashMap` and existing per-state synchronized methods. Acquire locks only in aggregate-mutex-then-state order. No code path may acquire the aggregate mutex while already owning an `AggState` monitor.
- `listFingerprints` copies/snapshots at most the bounded map while holding the aggregate mutex, then sorts and applies the existing response limit outside the mutex. Response fields, sorting criterion, and caller-visible fingerprint contract remain unchanged.
- Do not add eviction logs/TraceStore rows containing fingerprints, messages, errors, data, session identifiers, or request content. No eviction telemetry is required for this deterministic local capacity invariant.
- Do not modify synchronous NDJSON behavior in rank 10; it is the separate authoritative rank 21.

---

### Task 1: Refresh gates and freeze the dirty owner

- [x] **Step 1: Reconfirm active root and overlap state**

  Verify Java 17, branch/HEAD, root source-set declarations, exact target tracking/status/hash, `.git/index.lock`, worktrees that contain the target, PatchDrop inventory, source-edit leases, and any recent external writer evidence. The already-dirty target is not itself a HOLD; preserve its unrelated hunks. HOLD only this lane on a live target writer/lease, changed preimage, index lock, or ambiguous PatchDrop owner.

- [x] **Step 2: Freeze the exact aggregation chain**

  Record the event-ring cap/insertion path, `byFingerprint` declaration, `decide` admission/update path, `AggState` retained fields, `listFingerprints` full-map iteration/sort, direct controller/page/DebugCopilot consumers, and the lack of any active remove/clear/expiry/cap path. Record that `enabled` defaults true and rate-window reset does not remove the map key.

- [x] **Step 3: Run the repository three-way source-edit preflight**

  Freeze at most 20 redacted evidence rows for scenario `S_DEBUG_FINGERPRINT_RETENTION_BOUND`. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`; repeat neutral adjudication with support packets in A-B and B-A order. Require stable `APPLY`, identical decisive evidence, and score at least 50. The negative packet must challenge global-lock latency, deadlock order, LRU determinism, ring/aggregate coupling, invalid cap handling, re-entry semantics, raw-fingerprint leakage, and whether a general cache/sweeper is unjustified.

- [x] **Step 4: Acquire the Desktop source-edit lease**

  Acquire the repository source-edit lease for topic `priority-100-cohort-c5-debug-fingerprint-retention`, freeze the production preimage again immediately before every hunk, and release the lease in `finally`.

---

### Task 2: Add focused RED contracts

**File:** `src/test/java/com/example/lms/debug/DebugEventStoreFingerprintRetentionTest.java`

- [x] **Step 1: Add deterministic ring/cache separation RED**

  Create an enabled store with NDJSON disabled, `maxSize=10`, positive aggregate cap `2`, a long window, a high per-window rate limit, and no sleep. Emit `fp-a`, `fp-b`, `fp-c`. Require the ring to retain all three events while `listFingerprints(10)` exposes exactly two aggregates: `fp-b` and `fp-c`. This must fail on current production because all three aggregation states survive.

- [x] **Step 2: Add hot-state preservation and re-entry RED**

  With cap `2` and `maxPerWindow=1`, emit `fp-hot`, `fp-cold`, `fp-hot` again, then `fp-new`. Require `fp-hot` to survive as most recently touched with `windowCount=2`, `suppressedInWindow=1`, `total=2`, and `totalSuppressed=1`; require `fp-cold` to be evicted. In a separate test, evict `fp-a`, emit it again, and require its new retained aggregate to start at `windowCount=1`, `total=1`, and zero suppressed counts. No `Thread.sleep` may be used.

- [x] **Step 3: Add strict concurrent-cap RED**

  Use a test-owned bounded executor, a start barrier, and more unique fingerprints than a small configured cap. Wait on every task with bounded futures, shut down only the test executor in `finally`, and require no task failure, no null/malformed aggregate row, all expected recent ring events up to its independent larger limit, and aggregate result size exactly at or below the configured cap. Do not assert a particular eviction identity under concurrency.

- [x] **Step 4: Add default/fallback and privacy stability contracts**

  Prove that a missing/nonpositive aggregate cap follows the positive ring cap and never becomes unbounded. Use fixed low-cardinality fingerprints in public assertions. Assert the new source contains no eviction logging/tracing of the evicted key and retain the existing redaction fixture as the authority for payload/message/error secrecy; do not redefine the established caller-visible fingerprint field in this cohort.

- [x] **Step 5: Run focused RED**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.debug.DebugEventStoreFingerprintRetentionTest" `
    --tests "com.example.lms.debug.DebugEventRedactionTest" `
    --no-daemon `
    --project-cache-dir "C:\AbandonWare\gradle-project-cache\codex-priority100-rank10"
  ```

  Expected: only the new bounded-retention/configuration assertions fail; the existing redaction fixture remains green.

---

### Task 3: Implement the bounded aggregate cache

**File:** `main/java/com/example/lms/debug/DebugEventStore.java`

- [x] **Step 1: Add the bounded configuration and state invariant**

  Add `@Value("${lms.debug.events.rate.max-fingerprints:0}")` for `maxFingerprints`, one private aggregate-state mutex, and one store-local touch sequence. Add `lastTouchedOrder` to `AggState`. The effective-cap helper returns the configured positive value or `max(1, maxSize)`; it performs no logging, I/O, or mutation.

- [x] **Step 2: Make aggregate admission/update/eviction atomic**

  In `decide`, synchronize on the aggregate mutex. For an existing fingerprint, advance the touch sequence, update `lastTouchedOrder`, and invoke the existing `onEvent`. For a new fingerprint at capacity, scan the already bounded map, select the smallest `lastTouchedOrder` with key comparison only as a deterministic tie-break, remove exactly that mapping, insert one new state, advance/touch it, and invoke `onEvent`. The mutex must be released before `emit` sanitizes/builds/logs the resulting event. Do not call ring or NDJSON methods from the mutex.

- [x] **Step 3: Bound aggregate snapshots**

  In `listFingerprints`, hold the same aggregate mutex only while iterating the bounded map and taking existing `AggState.snapshot` values. Release it before sorting or creating the limited return view. Keep all existing response keys and descending `windowCount` sorting.

- [x] **Step 4: Run focused GREEN and inspect invariants**

  Re-run the focused command and require all new retention/concurrency tests plus `DebugEventRedactionTest` to pass. Inspect static lock ordering, confirm map removal exists only in the capacity admission helper, confirm ring code is unchanged, and confirm no new thread/executor/dependency/resource/telemetry was added.

---

### Task 4: Review, verify, and record rank 10

- [x] **Step 1: Run affected boundaries**

  Run the new retention fixture, `DebugEventRedactionTest`, `DebugEventTracePromotionServiceTest`, `DebugEventsDiagnosticsControllerFailureSignalTest`, `DebugEventsPageViewConfigTest`, and `SoakWebKpiMinuteSummaryLoggerDebugEventTest`. Record exact suite/test/failure/error/skipped totals. No browser, provider, credential, or database call is required.

- [x] **Step 2: Request independent implementation review**

  Supply the approved plan hash, frozen pre/postimages, exact C5 journal/diff, RED/GREEN outputs, concurrency topology, cap/lock/removal counts, and privacy scan. Require `SPEC`, `QUALITY`, Critical/Important findings, and `APPROVE|HOLD|REJECT`. Any Critical or Important finding gets a focused RED before correction.

- [x] **Step 3: Broaden verification proportionally**

  Run Desktop-isolated `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes`, then host-isolated full `test --rerun-tasks --fail-fast` and a fresh `bootJar`. Browser proof is not needed for an in-memory Java retention invariant and must not substitute for the concurrent focused test.

- [x] **Step 4: Integrity and terminal disposition**

  Run exact-target `git diff --check`, postimage hashes, and a count-only secret scan. Record rank 10 as `FIXED` only after strict-cap RED/GREEN proof, approved independent review, fresh affected/broad verification, and JAR class-entry proof. Release the source-edit lease in `finally`. Do not claim the separate rank 21 synchronous NDJSON defect fixed.
