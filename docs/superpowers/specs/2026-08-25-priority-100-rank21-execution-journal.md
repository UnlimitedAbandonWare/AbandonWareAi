# Priority 100 rank 21 execution journal

## Outcome

- Production seam:
  `main/java/com/example/lms/debug/DebugEventStore.java`
- Disposition change: rank `21`, `HOLD -> FIXED`
- Distinct production patches: `1`
- Actual HOLD reduction: `1`
- Final terminal totals: `FIXED=24`, `NO_PATCH_NEEDED=31`, `HOLD=27`,
  `EVIDENCE_NEEDED=18`
- Next requested target: rank `22`, the active `AutoEvolveDebugStore` NDJSON
  rotation `Files.list` stream-close seam

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
- observed IDE Git-task evidence count: `0`
- active source-edit lease count before preflight: `0`
- active top-level PatchDrop patch count: `0`
- staged path count: `0`
- pre-rank broad porcelain entries: `1699`
- pre-rank broad porcelain SHA-256:
  `23DDBD8780019EF7D8EE0B697CD68046F7A9776E1FA2A13C3BC378FC3AB35FAA`
- active sourceSet: root `main/java`; active test sourceSet: `src/test/java`
- active owner: tracked root `@Component`
  `com.example.lms.debug.DebugEventStore`
- active request path:
  `ChatApiController -> ChatService -> ChatWorkflow -> DebugEventStore.emit`
- default behavior evidence: NDJSON mirror is enabled when the property is
  missing; the tracked YAML supplies the directory fallback only
- frozen production preimage SHA-256:
  `F8AD26DC99D465EA8992C19B0713FCD054489AC303671B5339DDC4C79579AE84`
- the owner was already dirty from the completed rank-10 fingerprint-cap and
  redaction work; its rank-21 `logJson` / `mirrorNdjson` body still matched HEAD
  exactly, and the rank-10 plan explicitly excluded synchronous NDJSON work as
  separate rank 21 ownership
- the focused test and this journal did not exist and were not ignored
- route: `DIRECT`; optional agents spawned: `0`; all reads, edits, integration,
  verification, and judgment remained in the parent task

## Root cause

Every accepted request-side event called `logJson`, which serialized the event,
logged it, and directly called a `synchronized mirrorNdjson`. That monitor was
held while `Files.createDirectories` and `Files.writeString(CREATE, APPEND)` ran.
A slow, full, or remote-backed debug directory therefore blocked the emitting
request thread and serialized every other emitter behind the same file-I/O
critical section. The in-memory ring and fingerprint cap did not bound or
remove this latency path.

## Frozen EvidenceSnapshot

- evidenceRowCount: `16`
- evidenceSummaryChars: `2398`
- evidenceSnapshotHash:
  `EEE89319B10302AF7DD012E7723597D7D42BD108B4095D41F3B753E3251E42A1`
- rawPromptStored: `false`

```text
E21-01|root_branch_head|C:/AbandonWare/demo-1/demo-1/src|codex/owned-runtime-browser-restart|0796a3c5b29bbb08c3314bd40649d856d4a7bce6
E21-02|active_sources|build.gradle.kts:334-350 main/java main/resources src/test/java
E21-03|active_owner|tracked @Component main/java/com/example/lms/debug/DebugEventStore.java
E21-04|owner_preimage|sha256=F8AD26DC99D465EA8992C19B0713FCD054489AC303671B5339DDC4C79579AE84;dirty rank10 aggregate/redaction hunks;rank21 lines 459-488 match HEAD body
E21-05|runtime_default|DebugEventStore.java:79-83 ndjson dir fallback and enabled=true;application.yml:457-462 active dir binding
E21-06|request_entry|ChatApiController.java:2261,3778 -> ChatService.java:30-35 -> ChatWorkflow.java:792,1125
E21-07|request_emitters|ChatWorkflow.java:2589,3010,6123 call active DebugEventStore.emit
E21-08|inline_chain|DebugEventStore.java:177-179 addToRing then logJson;459-465 serializes, console logs, calls mirrorNdjson inline
E21-09|blocking_io|DebugEventStore.java:473 synchronized mirrorNdjson;478-483 createDirectories and Files.writeString CREATE+APPEND under request thread monitor
E21-10|test_gap|no DebugEventStoreNdjsonConcurrencyTest;new tracked test path absent and nonignored
E21-11|affected_contract|DebugEventRedactionTest.java:224-248 requires fixed redacted mirror failure log and no throwable rendering
E21-12|dirty_ownership|rank10 plan explicitly owns aggregate hunk and excludes synchronous NDJSON as separate rank21;target I/O hunk unchanged from HEAD
E21-13|git_safety|gitProcesses=0;indexLock=false;ideGitEvidence=0;staged=0;topLevelPatch=0;porcelain=1699
E21-14|lease|source-edit-locks=0;activeCount=0;corruptCount=0;expiredCount=0
E21-15|verification|Java17;focused DebugEventStore tests;affected debug tests;checkSourceSetHygiene;checkLangchain4jVersionPurity;full test rerun
E21-16|declared_writes|DebugEventStore.java;new DebugEventStoreNdjsonConcurrencyTest.java;new rank21 journal;terminal ledger only
```

## Source-edit three-way preflight

- processMode: `single-agent-logical-roles`
- canonicalQueryCount: `3`
- Positive and Negative scenario IDs:
  `S21_REQUEST_NONBLOCKING,S21_BOUNDED_FIFO,S21_LIFECYCLE_REDACTION`
- scenarioCoverageEqual: `true`
- forward/reverse decisive evidence IDs:
  `E21-03,E21-04,E21-06,E21-07,E21-08,E21-09,E21-10,E21-11,E21-12,E21-13,E21-14,E21-15`
- decisiveEvidenceEqual: `true`

POSITIVE_QUERY required three falsifiable outcomes:

- a latch-blocked file writer must not keep request-side `emit` calls open;
- with one running write and a FIFO capacity of one, the second line must remain
  queued, the third must be rejected without `CallerRuns` or blocking, and the
  two accepted lines must retain FIFO order;
- only the already sanitized JSON line may enter the queue, and the owned writer
  must terminate through the component lifecycle.

NEGATIVE_QUERY used the exact same scenario IDs and rejected four unsafe
shortcuts:

- an unbounded executor merely moves the retention defect off the request
  thread;
- `put`, `CallerRunsPolicy`, or a blocking rejection handler recreates request
  latency under saturation;
- queueing the pre-sanitized event extends private-payload retention;
- an unmanaged permanent thread creates a new application-shutdown leak.

The selected goal therefore changes only explicit NDJSON filesystem mirroring.
JSON serialization and the existing `DEBUG_EVENT_JSON` log call remain on the
caller; this patch does not claim that all logging is asynchronous.

NEUTRAL_QUERY:

- forwardOrder / reverseOrder:
  `[POSITIVE_QUERY,NEGATIVE_QUERY]` /
  `[NEGATIVE_QUERY,POSITIVE_QUERY]`
- forwardVerdict / reverseVerdict: `APPLY / APPLY`
- orderStable: `true`
- score inputs: evidence `0.98`, causal `0.98`, verification `0.95`, user value
  `0.90`, reversibility `0.95`, cost efficiency `0.90`, time fit `0.90`, blast
  radius `0.20`, ambiguity `0.10`, authority expansion `0.00`
- goalScore: `89.35`
- verdict: `APPLY`
- nextWorkflow: `existing-source-owner-guard`

## Lease and deterministic RED

- lease topic: `priority100-rank21-debug-ndjson-fifo`
- lease owner: `codex-rank21-20260825`
- TTL: `180` minutes
- lease acquisition result: `source-edit-locks=1`
- source SHA immediately before the production patch:
  `F8AD26DC99D465EA8992C19B0713FCD054489AC303671B5339DDC4C79579AE84`
- focused test:
  `src/test/java/com/example/lms/debug/DebugEventStoreNdjsonConcurrencyTest.java`
- final focused test SHA-256:
  `E482EBB0207A26C42DD0774655F2B7D93C84B31DDB82FBBC81008DD6F5661963`
- canonical RED command reached `compileTestJava` and failed with exactly six
  missing-symbol errors for the bounded writer interface, injection
  constructor, queue/drop diagnostics, and lifecycle methods
- expected RED reason: the active owner had no bounded asynchronous NDJSON
  writer seam; production remained unchanged until this failure was observed

The final fixture uses a real `DebugEventStore`, one real daemon writer thread,
a latch-blocked writer, an `ArrayBlockingQueue` capacity of one, bounded
request-side execution, real serialized/redacted JSON lines, exact accepted
fingerprint order, exact rejection count, and owned shutdown assertions. It
does not rely on source text, filesystem timing, sleep, or mock call counts.

## Single minimal production patch

- `mirrorNdjson` now captures only the already serialized and sanitized JSON
  line, directory, and date-derived filename, then submits a write task without
  performing directory or file I/O on the caller;
- one lazily created `ThreadPoolExecutor` has core `0`, maximum `1`, a FIFO
  `ArrayBlockingQueue`, a daemon thread factory, and `AbortPolicy`;
- queue capacity defaults in code to `256`, invalid/nonpositive values fall
  back to that default, and positive values are capped at `8192`;
- saturation deterministically retains the running and older queued lines and
  drops the newest line; the store records a count-only total and emits fixed
  `queue_saturated` or `writer_closed` reason codes at power-of-two counts;
- all `createDirectories` and `writeString(CREATE, APPEND)` operations execute
  only on the single writer, preserving accepted FIFO order without a global
  request-thread I/O monitor;
- `@PreDestroy` stops admission, attempts a bounded one-second drain, then
  interrupts the owned writer if necessary while preserving an interrupted
  shutdown caller's interrupt status;
- the existing ring, fingerprint aggregation, event schema, console JSON log,
  redaction, failure-log format, public emit API, and NDJSON directory/enabled
  property names remain unchanged;
- no resource file, dependency, database, provider, prompt boundary, protected
  key, or `opnessl` value/name/format/structure changed;
- final production SHA-256:
  `A8527F2DF5A4B7E288D1E489EE34A2371358729E6FADE2779905EFB2041953DF`

## GREEN and regression proof

- focused suite: `1/1` GREEN, failures/errors/skips `0/0/0`
- focused XML SHA-256:
  `7B3117E55358421BF631529BB7CF68CD10205700B0DEF732B72E58D23496C638`
- affected suites: new concurrency fixture, fingerprint retention, debug-event
  redaction, trace promotion, diagnostics failure signal, and SSE lifecycle;
  suites/tests `6/50`, failures/errors/skips `0/0/0`
- `checkSourceSetHygiene`: `BUILD SUCCESSFUL`; the expected inactive-present
  app source notice remains informational
- `checkLangchain4jVersionPurity`: `BUILD SUCCESSFUL`; LangChain4j remains
  exactly `1.0.1`
- full command: `gradlew.bat test --rerun-tasks --no-daemon` with split host
  `desktop-rank21`, isolated Gradle user home, and isolated project cache
- full result: `BUILD SUCCESSFUL in 3m 35s`
- XML aggregation: suites `1018`, tests `6488`, failures `0`, errors `0`,
  skipped `3`
- full XML result-manifest SHA-256:
  `2B83113AD9A57F5A48107F0F821D7A49DA45650F1CFD39A834958C778229E5A0`
- `git diff --check` on the tracked production owner: exit `0`
- changed source/test high-confidence secret-pattern hits: `0`
- changed source/test `opnessl` occurrences: `0`
- live disk-stall injection, browser, runtime, provider, and wire execution:
  `not_observed`; the deterministic injected writer proves the in-process
  concurrency contract without making an external-success claim

## Lease release

- lease release requested / completed UTC:
  `2026-08-25T03:29:43.5440432Z` /
  `2026-08-25T03:29:43.6288336Z`
- lease release result: `source-edit-locks=0`
- post-release active/corrupt/expired lease counts: `0/0/0`

## Final preservation

- final branch / HEAD:
  `codex/owned-runtime-browser-restart` /
  `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- final worktree: `C:/AbandonWare/demo-1/demo-1/src`
- `.git/index.lock` present: `false`
- Git/Git-LFS process count: `0`
- observed IDE Git-task evidence count: `0`
- active top-level PatchDrop patch count: `0`
- staged path count: `0`
- post-release active/corrupt/expired lease counts: `0/0/0`
- final broad porcelain entries after documentation: `1701`
- new porcelain rows introduced by rank 21: the focused regression test and
  this execution journal
- the production owner and terminal ledger were already porcelain rows at
  intake, so neither added a new path-set row
- final porcelain entries after excluding the two new rows: `1699`
- final filtered porcelain SHA-256:
  `23DDBD8780019EF7D8EE0B697CD68046F7A9776E1FA2A13C3BC378FC3AB35FAA`
- filtered status equals the exact pre-rank count and hash: `true`
- tracked `git diff --check`: exit `0`
- trailing-whitespace lines across the four declared paths: `0`
- terminal-ledger SHA-256 after rank-21 disposition update:
  `5EDB00800C0E3BB27B4FBE0783C58CA2A5558509482F1C563035AA3CB3A8EF7C`

No pre-existing porcelain row was removed or normalized, and no whole file
outside the declared four-path set was rewritten.
