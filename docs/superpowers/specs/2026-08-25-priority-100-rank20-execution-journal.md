# Priority 100 rank 20 execution journal

## Outcome

- Production seam:
  `main/java/ai/abandonware/nova/orch/storage/FileDegradedStorage.java`
- Disposition change: rank `20`, `HOLD -> FIXED`
- Distinct production patches: `1`
- Actual HOLD reduction: `1`
- Final terminal totals: `FIXED=23`, `NO_PATCH_NEEDED=31`, `HOLD=28`,
  `EVIDENCE_NEEDED=18`
- Next requested target: rank `21`, the active request-thread synchronous
  `DebugEventStore` NDJSON write seam

No commit, staging, push, deployment, database change, provider call, protected
configuration edit, dependency change, or `opnessl` mutation was performed.
Provider and wire execution remain `not_observed`.

## Fresh owner and preimage proof

- repository / worktree: `C:\AbandonWare\demo-1\demo-1\src`
- branch: `codex/owned-runtime-browser-restart`
- HEAD: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- Java: `17.0.13`
- `.git/index.lock` present: `false`
- Git/Git-LFS process count: `0`
- active source-edit lease count before preflight: `0`
- active top-level PatchDrop patch count: `0`
- staged path count: `0`
- pre-rank broad porcelain entries: `1696`
- pre-rank broad porcelain SHA-256:
  `F7D7D572F23586507A7FEF4922BC362AB50E4DC8DBB7090E0C535103EFC88A52`
- active sourceSet: root `main/java`; active test sourceSet: `src/test/java`
- active bean owner: `NovaOrchestrationAutoConfiguration.degradedStorage`,
  enabled when the property is missing
- active caller: request-adjacent
  `MemoryDegradedAspect.aroundReinforce -> DegradedStorage.putPending`
- default mode evidence: `enabled=true`, path
  `./data/degraded-memory.jsonl`, `format=auto`, `enforceOnWrite=true`
- frozen production preimage SHA-256:
  `5924BC157386F63BFB5EF1D1AFB4A9AD6785A471A78F3E9392589AD14FC734C7`
- production owner was tracked and clean; the focused test and this journal did
  not exist and were not ignored
- route: `DIRECT`; optional agents spawned: `0`; all reads, edits, integration,
  verification, and judgment remained in the parent task

## Root cause

Default JSONL `putPending` acquired the singleton storage's one
`ReentrantLock`, appended a record, synchronously ran `sweepInternal`, and
released the lock only after full-file JSONL reads and possible rewrites of
pending, inflight, and quarantine data. A second request-side put therefore
waited behind unrelated retention I/O. Directory mode used the same combined
write-plus-sweep critical section even though its event filenames are unique.

## Frozen EvidenceSnapshot

- evidenceRowCount: `18`
- evidenceSnapshotHash:
  `83ED21C91FA955EA6EFCD3D26592A934F15EF893DDE35A65B5B9E7FC736C6500`
- rawPromptStored: `false`

```text
E01|root=C:\AbandonWare\demo-1\demo-1\src;branch=codex/owned-runtime-browser-restart;head=0796a3c5b29bbb08c3314bd40649d856d4a7bce6
E02|activeSourceSet=main/java;activeTestSet=src/test/java;build.gradle.kts:334-350
E03|owner=main/java/ai/abandonware/nova/orch/storage/FileDegradedStorage.java;tracked=true;dirty=false;sha256=5924BC157386F63BFB5EF1D1AFB4A9AD6785A471A78F3E9392589AD14FC734C7
E04|bean=NovaOrchestrationAutoConfiguration.degradedStorage;enabledMatchIfMissing=true;lines=344-349
E05|default=enabled:true,path:./data/degraded-memory.jsonl,format:auto,enforceOnWrite:true;configLines=NovaOrchestrationProperties:866-942;application.yml:704-707
E06|activeCaller=MemoryDegradedAspect.aroundReinforce->storage.putPending;request-adjacent after reinforceFromGuardDecision;lines=35-95
E07|rootCause=putPending acquires instance lock at 178, appends JSONL at 192, runs sweepInternal at 195-196, releases at 199
E08|sweepCost=sweepInternal JSONL reads and may rewrite pending,inflight,quarantine;lines=991-1016,1247-1413
E09|concurrencyEffect=second putPending on singleton bean waits behind blocked full sweep because both require same lock
E10|existingDirectConcurrencyTests=0;only DegradedStorageDrainerRedactionContractTest exists for storage package
E11|newTest=src/test/java/ai/abandonware/nova/orch/storage/FileDegradedStorageConcurrencyTest.java;exists=false;ignored=false;owner=current-session
E12|declaredWrites=FileDegradedStorage.java,new concurrency test,new rank20 journal,terminal ledger only
E13|candidatePatch=durable JSONL ingress sidecar plus short ingress lock;put does not acquire maintenance lock;opportunistic sweep uses tryLock;claim/stats/peek/sweep merge ingress
E14|preservation=crash between ingress merge append/delete may duplicate but does not lose;new puts during sweep remain durable and are merged by next owner operation
E15|safety=gitProcesses0,indexLockFalse,activeLease0,topLevelPatch0,staged0,diffCheckExit0,java17
E16|baselineStatus=count1696;sha256=F7D7D572F23586507A7FEF4922BC362AB50E4DC8DBB7090E0C535103EFC88A52
E17|verification=focused FileDegradedStorageConcurrencyTest;affected NovaRuntimeRedactionContractTest plus storage tests;checkSourceSetHygiene;checkLangchain4jVersionPurity;full test --rerun-tasks
E18|constraints=no config/dependency/opnessl/secret/provider/db/git-stage/commit/push/deploy changes
```

## Source-edit three-way preflight

- processMode: `single-agent-logical-roles`
- canonicalQueryCount: `3`
- Positive and Negative scenario IDs:
  `S20-CONTENTION,S20-DURABILITY,S20-MODE-BOUNDARY`
- scenarioCoverageEqual: `true`
- forward/reverse decisive evidence IDs:
  `E03,E05,E07,E08,E09,E11,E13,E14,E15,E17`
- decisiveEvidenceEqual: `true`

POSITIVE_QUERY required that a blocked default JSONL sweep not delay a second
durable put, that both accepted records remain claimable, and that mode-specific
semantics remain explicit.

NEGATIVE_QUERY used the same IDs and rejected three unsafe shortcuts:

- routing `enforceOnWrite=false` through a deferred ingress path could leave an
  otherwise direct append outside the configured file;
- removing locking around concurrent append and whole-file replacement could
  lose a record;
- the active singleton in-process defect does not prove or authorize a claim of
  cross-process file safety.

The selected goal therefore keeps direct serialized JSONL append when
enforcement is disabled, uses a separately locked on-disk ingress only for the
default enforce-on-write route, and merges that ingress under maintenance
ownership before consumer/diagnostic reads.

NEUTRAL_QUERY:

- forwardOrder / reverseOrder:
  `[POSITIVE_QUERY,NEGATIVE_QUERY]` /
  `[NEGATIVE_QUERY,POSITIVE_QUERY]`
- forwardVerdict / reverseVerdict: `APPLY / APPLY`
- orderStable: `true`
- score inputs: evidence `0.95`, causal `0.92`, verification `0.92`, user value
  `0.80`, reversibility `0.90`, cost efficiency `0.78`, time fit `0.90`, blast
  radius `0.25`, ambiguity `0.18`, authority expansion `0.00`
- goalScore: `81.55`
- verdict: `APPLY`
- nextWorkflow: `existing-source-owner-guard`

## Lease and deterministic RED

- lease topic: `priority100-rank20-file-degraded-storage-lock`
- lease owner: `codex-rank20-20260825`
- TTL: `180` minutes
- lease acquisition result: `source-edit-locks=1`
- source SHA immediately before the production patch:
  `5924BC157386F63BFB5EF1D1AFB4A9AD6785A471A78F3E9392589AD14FC734C7`
- focused test:
  `src/test/java/ai/abandonware/nova/orch/storage/FileDegradedStorageConcurrencyTest.java`
- focused test SHA-256:
  `1C6AF90468AA8D67042ED3F878F43AE74171716EE18B3FB966DD2E34D2FC01EF`
- canonical RED: tests `1`, failures `1`, errors `0`, skipped `0`
- expected failure: the second put's bounded `Future.get(750 ms)` raised
  `TimeoutException` while the first put was paused inside the actual full JSONL
  `ObjectMapper.readValue`

The first fixture run was not accepted as RED: an unconfigured test mapper
could not serialize `Instant`, so fail-soft storage never reached the latch.
Only the test fixture was corrected with `findAndRegisterModules`; production
remained unchanged until the canonical timeout RED above was observed.

The final fixture uses real `FileDegradedStorage`, real temporary files, two
real worker threads, a latch at the actual JSON parse boundary, and assertions
on the real `claim(10)` result rather than mock calls or source text.

## Single minimal production patch

- JSONL enforce-on-write puts append to an on-disk `.incoming` sidecar under a
  dedicated short ingress lock and never acquire the maintenance lock;
- the put may perform an opportunistic sweep only through `tryLock`, so an
  already-running full sweep never queues the request writer;
- maintenance ownership rotates ingress atomically to `.incoming.draining`,
  releases the ingress lock before parsing/appending, and merges at most two
  batches before claim, sweep, stats, or peek observes the main pending file;
- a crash between main-file append and sidecar deletion leaves the draining
  batch available for replay and may create duplicates; within the class's
  existing best-effort, non-fsync file contract, deletion occurs only after all
  batch appends report success, preserving its duplicate-over-loss preference;
- `enforceOnWrite=false` retains the original direct serialized append;
- directory-mode puts use their unique temporary/final filenames and only try
  opportunistic maintenance after the write;
- pending byte accounting includes both ingress paths;
- no configuration key/value, dependency, public interface, executor, resource,
  database, provider, or prompt boundary changed;
- cross-process file safety was not changed or claimed;
- final production SHA-256:
  `289520B6111EC84EB546014A148ECDD936FD4DEC103F25476C41C44A80E2F848`

## GREEN and regression proof

- focused suite: `1/1` GREEN, failures/errors/skips `0/0/0`
- focused XML SHA-256:
  `83E509B2C16A97B2BD070403D2D526E0203113980E21CF0A0ED1060D65C8B500`
- affected suites: storage concurrency/drainer, runtime redaction,
  request-side degraded-memory aspect, outbox API diagnostics, runtime
  diagnostics, and UI visibility; `28/28` GREEN, failures/errors/skips `0/0/0`
- `checkSourceSetHygiene`: `BUILD SUCCESSFUL`; the expected inactive-present
  app source notice remains informational
- `checkLangchain4jVersionPurity`: `BUILD SUCCESSFUL`; LangChain4j remains
  exactly `1.0.1`
- full command: `gradlew.bat test --rerun-tasks --no-daemon` with split host
  `desktop-rank20`, isolated Gradle user home, and isolated project cache
- full result: `BUILD SUCCESSFUL in 3m 34s`
- XML aggregation: suites `1017`, tests `6487`, failures `0`, errors `0`,
  skipped `3`
- full XML result-manifest SHA-256:
  `A87FEA209A7C2F3BC7EA2512E761E73BEFEC3C7BDB0A34D54028C5FC9DAFDF93`
- `git diff --check`: exit `0`
- changed source/test secret-pattern hits: `0`
- changed source/test `opnessl` occurrences: `0`
- runtime/provider/wire execution: `not_observed`
- process-crash fault injection: `not_observed`; recovery behavior above is a
  source-contract claim, not a power-loss durability claim

## Final preservation and lease release

- lease release requested / completed UTC:
  `2026-08-25T02:52:03.8989457Z` /
  `2026-08-25T02:52:03.9945253Z`
- lease release result: `source-edit-locks=0`
- post-release active/corrupt/expired lease counts: `0/0/0`
- `.git/index.lock` present: `false`
- Git/Git-LFS process count: `0`
- active top-level PatchDrop patch count: `0`
- source/test preservation check before documentation:
  broad porcelain entries `1698`; after excluding the production owner and
  focused test, entries `1696` with SHA-256
  `F7D7D572F23586507A7FEF4922BC362AB50E4DC8DBB7090E0C535103EFC88A52`
- filtered status equals the exact pre-rank status: `true`
- staged path count: `0`
- final branch / HEAD:
  `codex/owned-runtime-browser-restart` /
  `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- final worktree: `C:/AbandonWare/demo-1/demo-1/src`
- final broad porcelain entries after documentation: `1699`
- new porcelain rows introduced by rank 20: the clean production owner, the
  focused regression test, and this execution journal
- the terminal ledger was already a porcelain row at intake, so its status code
  remained unchanged
- final porcelain entries excluding the three new rows: `1696`
- final filtered porcelain SHA-256:
  `F7D7D572F23586507A7FEF4922BC362AB50E4DC8DBB7090E0C535103EFC88A52`
- final filtered status equals the exact pre-rank status: `true`

No pre-existing porcelain row was removed or normalized, and no whole file
outside the declared four-path set was rewritten.
