# Priority-100 Risk Burn-Down Design

- Date: 2026-08-24
- Status: In-chat design approved; written specification awaiting review
- Workspace: `C:\AbandonWare\demo-1\demo-1\src`
- Goal: Resolve the current audit's 100 prioritized defects within a nine-hour maximum execution budget, without redefining success around a smaller green subset
- Change authority: Scoped, reversible repository changes are requested; commits, pushes, deployments, production/database mutations, credentials, ACL changes, destructive cleanup, and new production dependencies remain unauthorized

## 1. Outcome

This work burns down the current audit backlog in risk order. P1 security, durable-data consistency, resource lifecycle, cancellation, privacy, artifact identity, and user-visible correctness come before lower-severity structure and maintainability work.

Each backlog item must end in exactly one evidence-backed terminal disposition:

- `FIXED`: the active owner was patched and the affected behavior passed fresh focused verification;
- `NO_PATCH_NEEDED`: live evidence disproved the original defect or proved that a concurrent/user change already corrected it, with the decisive command or test recorded;
- `EVIDENCE_NEEDED`: a required external/runtime artifact cannot be obtained locally, with one exact verification action recorded;
- `HOLD`: a repository gate makes the affected lane unsafe, with `holdScope`, `firstBlockingRule`, `blockingEvidence`, `independentWorkCompleted`, and `repositoryWideHold` recorded.

A partial mitigation is recorded as a completed sub-item, not as closure of its parent defect. In particular, changing enqueue order does not by itself prove durable process-restart recovery.

## 2. Authoritative Inputs and Freshness

The authoritative backlog is the 100-row audit delivered in the current Codex task, audit turn `01a03389-f33b-7092-9793-9fbe5a38d0c9`. The older untracked file `docs/audits/2026-08-13-demo1-source-audit-100-ledger.md` is a different audit and must not be substituted for this backlog.

Current intake evidence at design time:

- branch: `codex/owned-runtime-browser-restart`;
- HEAD: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`;
- worktree: heavily dirty, with 1,658 status entries observed during intake;
- no `.git/index.lock` observed;
- no active source-edit lease observed;
- no active top-level PatchDrop patch observed;
- four nested producer-pending directories observed;
- 36 worktrees observed, including prunable and detached worktrees;
- Java 17.0.13 and Gradle 8.7 were observed during the audit;
- the audit's full host-isolated test run reported 6,378 tests passing, zero failures, zero errors, and three skipped tests;
- audit-time green results are baseline evidence only and do not certify later edits.

Every target must be re-read and re-hashed immediately before its edit. Conversation history, this document, old reports, stale JARs, old browser captures, and prior test output cannot prove current ownership or behavior.

## 3. Constraints and Non-Goals

The execution must:

- preserve unrelated user changes and avoid broad formatting or cleanup;
- stay on active source sets proven by current Gradle configuration;
- use the repository's single application-source preflight before each independent mutation cohort;
- freeze one redacted evidence snapshot and run exactly the required positive, negative, and neutral queries before source mutation;
- require a stable `APPLY` verdict before the owner/lease/preimage guard;
- use focused RED evidence before changing defect behavior where a deterministic test is possible;
- make the smallest reversible patch at the active owner;
- verify the focused surface first and broaden only across affected boundaries;
- keep Browser, Computer, provider, Supabase, and database lanes separate and demand-driven;
- avoid raw prompts, responses, credentials, headers, cookies, private environment values, and sensitive identifiers in logs or reports;
- stop a lane on a changed preimage, overlapping writer, source-owner mismatch, index lock, relevant PatchDrop ambiguity, unavailable required runtime/secret, or failed neutral decision.

This design does not authorize:

- deleting inactive source trees, build directories, stale worktrees, artifacts, or user files;
- staging, committing, pushing, merging, or deploying;
- production/provider/database mutations;
- adding a new production dependency;
- treating a source contract or a green compile as behavioral proof;
- claiming that all 100 items are fixed when only a risk cohort is complete.

## 4. Architecture: Evidence-Gated Risk Burn-Down

The backlog is processed as a dependency-ordered sequence of small cohorts. A cohort groups defects only when they share an owner, state transition, or verification surface. Unrelated writes are not bundled merely to save command time.

```text
fresh target evidence
        |
        v
three-way source preflight ---- HOLD only the affected lane on unstable evidence
        |
        v
focused RED / behavior characterization
        |
        v
smallest owner-level patch
        |
        v
focused tests -> affected-boundary tests -> compile/classes -> broader suite as warranted
        |
        v
diff + pre/post hash + secret/redaction check
        |
        v
ledger disposition; then select the next highest-risk unblocked item
```

The nine hours are a maximum budget, not a requirement to consume time. Work stops earlier on complete resolution, decisive `NO_PATCH_NEEDED` evidence, a lane-local external blocker, missing operation-level authority, or exhausted safe work. If the budget expires with open items, they remain explicitly open; the result is not relabeled complete.

## 5. Cohort Order

### Cohort A: Durable state and idempotency

Primary items: 3, 4, and 5. Secondary evidence dependency: 2.

1. Fix pending-memory promotion so a failed enqueue cannot leave an `ACTIVE` row that the scheduler will never reclaim.
2. Make indexing reinforcement and cursor advancement depend on verified vector durability, not merely a normally returning `flush()` call.
3. Prove or add an atomic/leased image-job claim before a paid provider call.
4. Revalidate VectorStore queue capacity and failure-requeue behavior before deciding whether item 2 belongs in the same patch or a later bounded-queue cohort.

This cohort goes first because silent durable divergence and duplicate ingestion corrupt future behavior even when requests appear successful.

### Cohort B: Security and privacy boundaries

Primary items: 1, 6, 13, 55, 56, 57, and 58.

1. Bound webhook body intake before allocation and signature processing, rejecting oversize requests deterministically.
2. Remove internal/session correlation identifiers from outbound Brave headers and add an outbound-header denial test.
3. Bound or expire attachment retention without exposing session data.
4. Gate wiretap, stack traces, verbose binding/transaction/model logs, and destructive schema behavior behind safe environment/profile defaults.

### Cohort C: Cancellation, executors, queues, and retained state

Primary items: 2, 7-12, 19-26, and 28.

The design contract is uniform:

- preserve caller interrupt status;
- propagate or translate cancellation at the request boundary;
- interrupt owned running work when safe;
- place explicit capacity and retention bounds on queues/maps/rings;
- close owned executors/processes/resources during lifecycle shutdown;
- never hold a global monitor across unbounded disk or network I/O;
- test capacity recovery and shutdown, not only return latency.

### Cohort D: User-visible correctness and safety

Primary items: 14-17 and 27-32.

This cohort includes exact-token routing, vector identity/null safety, single-flight behavior, retrieval allocation, sanitizer reachability/enforcement, and fallback alternatives. It requires positive, negative, and neutral behavioral tests, including an exact Korean token-output regression.

### Cohort E: Build, artifact identity, and configuration ownership

Primary items: 33-60.

Resolve the active settings/build entry, dependency-lock reproducibility, host-split JAR selection and verification, active source/resource/FQCN ownership, exclusion policy, configuration conflicts, and unsafe profiles. Cleanup of accumulated outputs/worktrees remains report-only unless the user separately authorizes deletion.

### Cohort F: Evidence reliability and test hygiene

Primary items: 61-81.

Repair false-green scorecard inputs, stale evidence selection, completion boundaries, provider-attempt coverage, test isolation, timeout discipline, and broad-catch observability. Dirty-tree, untracked, deleted, worktree, pending PatchDrop, stale PID, and line-ending findings are tracked as operational risks; they do not authorize cleanup.

### Cohort G: Structural concentration and parallel ownership

Primary items: 82-100.

These findings are not solved by arbitrary class splitting. Each is closed only by a behavior-preserving extraction or an ownership consolidation justified by live call paths and focused tests. If no safe extraction fits the remaining budget, the item remains open with a concrete next seam; line-count reduction alone is not success.

## 6. First Executable Cohort: Exact Design

### 6.1 Pending-memory promotion

Observed active behavior at design time:

1. a scheduler claims a `PENDING` row using an existing lease mechanism;
2. it marks the row `ACTIVE`, releases the lease, and saves;
3. it then enqueues the vector operation;
4. enqueue failure is caught, but the row remains `ACTIVE`;
5. later ticks claim only `PENDING`, so the row is no longer retryable through this pipeline.

Immediate P1a repair:

- retain `PENDING` while the lease owner performs the enqueue;
- transition to `ACTIVE` only after enqueue acceptance succeeds;
- on enqueue exception, keep the row `PENDING`, release/expire the lease according to the existing repository contract, and preserve a redacted reason code;
- make repeated scheduler attempts safe through the existing deterministic segment/vector identity;
- add a behavior test that forces enqueue failure and proves the row is not terminal `ACTIVE` and is claimable again;
- add a success test proving exactly one legal transition to `ACTIVE` after accepted enqueue.

Durability caveat P1b:

Enqueue acceptance is not necessarily durable indexing completion. A process crash after the database transition but before vector persistence may still lose work. Therefore P1a is a mitigation sub-item, while the parent item remains open until one of these existing-compatible contracts is proven or implemented:

- a transactional outbox/index-request record committed with the row transition;
- a durable completion receipt reconciled by the scheduler;
- a repair scan that deterministically detects `ACTIVE` rows without the corresponding vector and requeues them.

No new state or table is introduced until live repository/schema evidence shows the smallest compatible choice. If database migration is required, that operation is separately gated and cannot be applied to a live database without authority.

### 6.2 Indexing scheduler durability gate

Observed active behavior at design time:

1. enqueue/flush work is attempted;
2. `VectorStoreService.flush()` can catch persistence failure internally, restore failed IDs to its queue, set error/backoff state, and return normally;
3. `IndexingScheduler` interprets normal return as `flushOk=true`;
4. reinforcement still runs unconditionally;
5. cursor advancement is conditional, causing the same source documents to be fetched and reinforced again on the next tick.

Repair contract:

- capture a pre-attempt durability checkpoint from the existing redacted `bufferStats()` surface;
- enqueue the batch and request flush;
- capture the post-attempt checkpoint;
- declare success only when all required conditions hold: a success checkpoint advanced for this attempt, `queued == 0`, and `lastError == null`;
- reinforce source segments and advance `lastFetchTime` only after that verified success;
- on failure/backoff/partial queue, do neither, retain retry position, and emit bounded reason-code evidence;
- avoid using wall-clock proximity alone as the success identity if concurrent flushes can occur; revalidate synchronization and, if necessary, introduce the smallest attempt-generation or result contract at the existing service seam;
- add mock behavioral tests for internal flush failure, active backoff, partially retained queue, and complete success;
- replace or supplement the existing source-order contract, which cannot detect swallowed internal failure.

If `bufferStats()` cannot distinguish this scheduler's attempt from unrelated concurrent work, the initial proposed predicate is insufficient. That yields a design-level `HOLD` for this patch until an attempt-scoped acknowledgement can be added safely; it is not acceptable to infer success from a global timestamp.

### 6.3 Image-job atomic claim

Observed active behavior at design time is a non-locking oldest-`PENDING` read followed by an `IN_PROGRESS` save before a paid provider call. Two nodes can select the same row.

Repair contract:

- claim one job atomically before provider invocation using an existing transaction/locking pattern if present;
- prefer a single conditional state transition or database-supported lock/skip-locked repository query;
- represent lease owner and expiry only if existing schema/pattern supports it; do not invent a second job framework;
- provider invocation proceeds only for the caller that observes a successful claim;
- terminal update must be conditional on retained ownership;
- add a concurrent test where two workers race and exactly one provider call occurs;
- add stale-claim recovery only if the current service already defines retry/lease semantics; otherwise keep recovery as a separately tracked sub-item.

## 7. State, Concurrency, and Failure Contracts

### State transitions

Every durable transition must have a single owner and an explicit trigger. A database state must not advertise downstream completion before the durable downstream acknowledgement on which that state semantically depends.

### Idempotency

Retries use stable, non-sensitive identities derived from existing document/segment/job keys. Idempotency must be verified at the side-effect boundary: vector writes, reinforcement persistence, and provider invocation. A deduplicating in-memory queue is insufficient evidence across process restart.

### Cancellation

`InterruptedException` handling must either rethrow or restore the interrupt flag before returning. Clearing the flag with `Thread.interrupted()` is prohibited at request cancellation seams. `Future.cancel(false)` is insufficient when owned blocking work must stop; tests must prove the executing task terminates or executor capacity recovers.

### Resource lifecycle

Any executor or child process created by an application component has an owner, a bounded queue/retention policy, and an idempotent shutdown path. Shutdown tests must cover both idle and in-flight work. Only task-owned processes may be terminated during verification.

### Error evidence

Diagnostics expose bounded reason codes, counts, timestamps, durations, and hashes. They do not expose raw queries, bodies, responses, keys, headers, cookies, session identifiers, or full exception bodies.

## 8. Dirty-Tree and Ownership Protocol

Before each write cohort:

1. refresh branch, HEAD, status for exact targets, worktrees, index lock, PatchDrop inventory, source-edit leases, and relevant ports;
2. prove the target belongs to the active source set and trace its current call path;
3. record exact target preimage hashes and inspect the user diff for overlap;
4. run the mandatory frozen-evidence three-way preflight;
5. enter the repository source-owner/lease guard only on stable `APPLY`;
6. run focused RED or behavior characterization;
7. use `apply_patch` for the smallest hunk;
8. immediately verify postimage hashes and inspect the resulting diff;
9. abort only the affected lane if the preimage changes or another writer appears.

Files already observed as heavily modified require heightened overlap review, including `N8nWebhookController`, `VectorStoreService`, `BraveSearchService`, `HybridWebSearchProvider`, `SelfAskWebSearchRetriever`, `LocalLlmProcessManager`, `DebugEventStore`, `QueryTransformer`, and the untracked `ImageJobRepository`. Clean status observed during design is not a future guarantee.

## 9. Verification Ladder

For each behavior patch:

1. deterministic focused RED or characterization;
2. focused unit/service test after patch;
3. negative, retry, cancellation, concurrency, or oversize case appropriate to the defect;
4. affected package/boundary tests;
5. `checkLangchain4jVersionPurity` and `checkSourceSetHygiene` when source/build boundaries are touched;
6. `compileJava` and `:app:classes` for affected Java/source-set work;
7. host-isolated broader tests when shared orchestration, persistence, provider, configuration, or lifecycle behavior changed;
8. `bootJar` when packaging/config/artifact behavior changed;
9. owned-runtime smoke only when runtime behavior cannot be proven below that layer;
10. browser proof only for user-visible UI/stream/cancel/token behavior;
11. provider wire proof only from an observed attempt with secrets redacted;
12. final `git diff --check`, exact diff review, target hashes, and count-only secret scan.

The baseline green suite does not waive new regression tests. Source-text ordering assertions are not accepted as the sole proof of runtime behavior.

## 10. Browser, Computer, Provider, and Database Lanes

- Browser is required for item 14 and any changed visible streaming/cancellation/UI behavior. It is not required for the first scheduler cohort.
- Computer Use is used only when shell, file, test, and Browser evidence cannot exercise a required Windows UI surface.
- Provider calls are not made decoratively. Missing keys, skipped canaries, HTTP delivery, or fallback responses do not prove provider generation or wire attempts.
- Supabase/database access remains read-only until project identity and operation authority are proven. Schema or data mutation is out of scope without explicit approval.
- Local runtime verification manages only processes started by this task and reports unrelated port owners without stopping them.

## 11. Time and Stop Policy

The maximum nine-hour execution window is allocated by risk and evidence, not equally across issue counts:

- approximately 35%: cohorts A and B;
- approximately 25%: cohort C;
- approximately 15%: cohort D;
- approximately 15%: cohort E;
- approximately 10%: cohorts F and G plus final integration reporting.

These percentages are adjustable when decisive evidence changes scope. The agent stops repeating a blocked or already-proven lane, continues independent safe work, and records one exact next verification action. No destructive cleanup is used to manufacture a cleaner result.

## 12. Completion Criteria

The goal is complete only when:

- all 100 ranked items have a terminal disposition with fresh evidence;
- every `FIXED` item has focused behavioral proof at its real owner;
- every changed cross-boundary surface has the appropriate broader Gradle/runtime/browser proof;
- P1 parent defects are not closed by partial mitigations;
- open external facts are labeled `EVIDENCE_NEEDED`, not inferred green;
- all `HOLD` entries contain the repository-required fields and remain lane-local unless every authorized lane is unsafe;
- the final diff preserves unrelated user changes and contains no unauthorized dependency, configuration, credential, database, deployment, cleanup, commit, or push action;
- the final report distinguishes audit-time baseline evidence from fresh post-change evidence.

## Appendix A: Authoritative 100-Item Inventory

| Rank | Priority | Defect or risk |
|---:|:---:|---|
| 1 | P1 | Unauthenticated `/hooks/n8n` reads an unbounded body before signature validation. |
| 2 | P1 | `VectorStoreService` queue is unbounded and requeues on failure. |
| 3 | P1 | `PendingMemorySoakScheduler` marks a row `ACTIVE` before enqueue and loses retry on enqueue failure. |
| 4 | P1 | `IndexingScheduler` reinforces after enqueue/flush failure and reprocesses the same source range. |
| 5 | P1 | Image-job claim is non-atomic across instances, allowing duplicate paid dispatch. |
| 6 | P1 | `BraveSearchService` sends internal `x-session-id` to an external provider. |
| 7 | P1 | `LocalLlmProcessManager.stop()` does not terminate its owned child process. |
| 8 | P1 | `HybridWebSearchProvider` clears request interrupts in repeated await paths. |
| 9 | P1 | Self-Ask timeout uses `cancel(false)`, leaving slow provider work running. |
| 10 | P1 | `DebugEventStore.byFingerprint` grows without eviction. |
| 11 | P1 | Admin SSE uses a static cached pool, zero-timeout emitters, no cap, and no shutdown. |
| 12 | P1 | `ChatRunRegistry` never evicts non-terminal runs. |
| 13 | P1 | `AttachmentService` maps have no bound, TTL, or session cleanup. |
| 14 | P1 | Browser exact-token request `TOKEN만 정확히 출력해` can take the direct-literal path incorrectly. |
| 15 | P1 | `FederatedEmbeddingStore` returns a UUID different from the stored vector identity. |
| 16 | P1 | `FederatedEmbeddingStore.add(String, Embedding)` can dereference a null segment. |
| 17 | P2 | `SingleFlightCache` permits multiple callers to execute the loader. |
| 18 | P2 | App-clean ONNX reranker is enabled by default but remains an unfinished pass-through and is currently excluded. |
| 19 | P2 | `QueryTransformer` clears interrupts and uses `cancel(false)` for an in-flight LLM task. |
| 20 | P2 | `FileDegradedStorage` performs writes and a full sweep under one lock. |
| 21 | P2 | `DebugEventStore` performs synchronous globally serialized NDJSON append on request threads. |
| 22 | P2 | Auto-evolve rotation leaks a `Files.list` stream. |
| 23 | P2 | Auto-evolve startup loading leaks a `Files.list` stream. |
| 24 | P2 | `InMemoryJobService` retains terminal jobs without a bound. |
| 25 | P2 | `InMemoryJobService` owns an executor without shutdown. |
| 26 | P2 | `ImageJobService` concurrently reads and mutates a `LinkedList` used for ETA. |
| 27 | P2 | `RetrievalOrderService` minimum allocation can fall below one. |
| 28 | P2 | `ModelRuntimeHealthTracker` snapshot retention may be unbounded; cardinality needs proof. |
| 29 | P2 | `AnswerSanitizer` implementations have no proven consumer on the answer path. |
| 30 | P2 | Game recommendation/universal guard sanitizer logs a violation but returns content unchanged. |
| 31 | P2 | `UniversalDomainSanitizer` logs and returns unchanged content with unfinished rules. |
| 32 | P2 | Active `FallbackHeuristics.suggestAlternatives` always returns empty. |
| 33 | P1 | `settings.gradle` shadows the intended `settings.gradle.kts` build policy. |
| 34 | P1 | Dependency locking is enabled without committed lockfiles, undermining reproducibility. |
| 35 | P1 | HTTPS startup script defaults to `build\libs` and can ignore the current host-split JAR. |
| 36 | P1 | Current host JAR and stale default June JAR have different artifact identities. |
| 37 | P2 | 6,492 host build directories create disk and audit cost. |
| 38 | P2 | Inactive `app/src/main/java` still contains 390 files and 16,503 lines. |
| 39 | P2 | Inactive and active trees contain 173 duplicate FQCNs. |
| 40 | P2 | Active root and app-clean source sets contain 12 duplicate FQCNs. |
| 41 | P2 | Duplicate policy is fail-open with stereotype filtering and `onDup=none`. |
| 42 | P2 | Ten duplicate classes are silently excluded during packaging. |
| 43 | P3 | Two hard-coded exclusions create manual ownership drift. |
| 44 | P2 | Root/app resource ownership has 28 collisions, including 22 differing files. |
| 45 | P2 | Root and app `logback-spring.xml` differ. |
| 46 | P2 | Two differing `configs/models.manifest.yaml` files are packaged. |
| 47 | P2 | Root and app tool manifests have materially different contracts. |
| 48 | P2 | `spring.factories` and auto-configuration imports differ across owners. |
| 49 | P2 | App resource exclusion covers only `plans/**` and `application*`. |
| 50 | P2 | Properties and YAML configure conflicting meanings for the same runtime settings. |
| 51 | P2 | Local-model base URL is environment-aware in one owner and literal localhost in another. |
| 52 | P2 | Kakao OAuth redirect URI is inconsistent. |
| 53 | P2 | OCR is hard-enabled in one configuration and environment-default false in another. |
| 54 | P2 | OCR confidence threshold conflicts at 0.78 versus 0.65. |
| 55 | P2 | Reactor Netty wiretap is always enabled in active base configuration. |
| 56 | P2 | Ultra profile always includes stack traces. |
| 57 | P2 | Ultra profile enables TRACE-level binder, transaction, and LangChain4j logging. |
| 58 | P2 | Learning profile uses destructive `ddl-auto=create-drop`. |
| 59 | P3 | At least five `OutboxSendTool` meanings exist; one no-op reports success and its active caller is unproven. |
| 60 | P2 | Gradle 9 incompatible deprecated-feature warnings remain. |
| 61 | P2 | Source health reports zero large files while 21 active files exceed 2,000 lines. |
| 62 | P2 | Scorecard uses fixed verification paths and accepts no fresh input argument. |
| 63 | P3 | Completion-audit evidence is roughly 770,853 seconds stale. |
| 64 | P3 | Database-gap evidence is roughly 770,968 seconds stale. |
| 65 | P3 | Boot proof is roughly 3,451,615 seconds stale and records failure. |
| 66 | P3 | Browser and Computer artifacts are stale. |
| 67 | P3 | Goal-next automatic status evidence is stale. |
| 68 | P2 | Fresh completion audit remains incomplete with six hard failures. |
| 69 | P2 | Stale default test failures can be confused with a fresh host-isolated 6,378-test green run. |
| 70 | P2 | Three skipped provider canaries mean green tests do not prove provider-wire execution. |
| 71 | P2 | Worktree isolation risk: 1,658 status entries and a very large diff. |
| 72 | P2 | 858 untracked entries include large main, src, scripts, and docs groups. |
| 73 | P2 | 171 tracked deletions have ambiguous ownership. |
| 74 | P2 | 36 worktrees include four prunable and six detached worktrees. |
| 75 | P3 | Four PatchDrop producer directories remain pending. |
| 76 | P3 | Three PID files appear stale. |
| 77 | P3 | 615 LF-to-CRLF warnings obscure diffs. |
| 78 | P3 | Tests use 43 `Thread.sleep` calls but only three `@Timeout` annotations. |
| 79 | P3 | Tests mutate system properties 45 times, risking isolation leakage. |
| 80 | P2 | No regression test covers `TOKEN만 정확히 출력해`. |
| 81 | P2 | 3,278 broad catches exist, with an estimated 13 lacking instrumentation. |
| 82 | P2 | `ChatWorkflow` is 13,086 lines, spans eight subsystems, and has about 170 broad catches. |
| 83 | P2 | `WebFailSoftSearchAspect` is 5,888 lines with about 151 broad catches. |
| 84 | P2 | `NaverSearchService` is 4,705 lines with about 75 broad catches. |
| 85 | P2 | `ChatApiController` is 4,300 lines, spans six subsystems, and has about 98 broad catches. |
| 86 | P2 | `HybridWebSearchProvider` is 4,078 lines with about 118 catches and repeated interrupt handling. |
| 87 | P2 | `UnifiedRagOrchestrator` is 3,878 lines and spans six subsystems. |
| 88 | P3 | `HybridRetriever` is 2,597 lines. |
| 89 | P3 | `EvidenceAwareGuard` is 2,497 lines. |
| 90 | P2 | `ModelRuntimeHealthTracker` is 2,410 lines and has unclear retention ownership. |
| 91 | P2 | `DynamicContextCompressor` is 2,248 lines and spans four subsystems. |
| 92 | P2 | Self-Ask retrieval is 2,221 lines and spans five subsystems. |
| 93 | P2 | `QueryTransformer` is 2,169 lines with about 68 catches. |
| 94 | P3 | `RagFailureBlackbox` is 2,116 lines and spans four subsystems. |
| 95 | P2 | `DynamicRetrievalHandlerChain` is 2,083 lines and spans seven subsystems. |
| 96 | P3 | `HybridWebSearchEmptyFallbackAspect` is 2,067 lines. |
| 97 | P3 | `ChatUiCoreHeartbeatProbe` is 1,991 lines and spans five subsystems. |
| 98 | P3 | `ChatStreamSignalBuilder` is 1,638 lines and spans six subsystems. |
| 99 | P2 | Parallel chat implementations obscure ownership, including orchestrator, legacy, and patch variants. |
| 100 | P2 | Whole active structure has 2,091 Java files, 212 cross-subsystem files, 47 files over 1,000 lines, and 41 runtime files over 1,000 lines. |

## 13. Review and Handoff

This written design must be reviewed before an implementation plan is produced. After approval, the next step is to invoke the Superpowers writing-plans workflow, convert cohorts into exact file/test/command steps, and then begin the repository-mandated source-edit preflight and focused RED for Cohort A.

No commit is created because commit authority was not granted.
