# Priority 100 ranks 22-26 execution journal

## Outcome

- Disposition changes: ranks `22`, `23`, `24`, `25`, and `26`, each
  `HOLD -> FIXED`.
- Distinct production owners patched: `3`.
- Actual HOLD reduction: `5`.
- Final terminal totals: `FIXED=29`, `NO_PATCH_NEEDED=31`, `HOLD=22`,
  `EVIDENCE_NEEDED=18`.
- Final branch / frozen HEAD:
  `codex/owned-runtime-browser-restart` /
  `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`.
- Canonical worktree: `C:\AbandonWare\demo-1\demo-1\src`.

No commit, staging, push, deployment, database mutation, provider call,
dependency change, protected configuration change, or `opnessl` mutation was
performed. A fresh task-owned local UI runtime and in-app Browser render were
observed only as HTTP/delivery/rendering evidence. No chat prompt was sent, so
provider/wire execution remains `not_observed`. Computer Use stopped before
input because it could not establish the existing Chrome window's current URL
with enough policy confidence; that attempt is not counted as Windows UI proof.

## Authorized stale-lock recovery

The rank-22/23 full verification created a new canonical `.git/index.lock`.
The same blocker was revalidated in three consecutive goal turns and the goal
was marked blocked. The user then explicitly directed immediate continuation,
which authorized recovery of that exact lock.

- canonical lock:
  `C:\AbandonWare\demo-1\demo-1\src\.git\index.lock`
- size: `0` bytes
- last write UTC: `2026-08-25T03:56:43.0784533Z`
- exclusive open: `true`
- Git/Git-LFS process count: `0`
- IDE process count: `0`
- source-edit active/corrupt/expired leases: `0/0/0`
- active top-level PatchDrop patches: `0`
- recovery action: moved, not deleted
- recoverable destination:
  `.git/codex-stale-locks/index.lock.20260825T044420117Z.stale`
- empty-file SHA-256 preserved: `true`
- canonical lock present after recovery: `false`

The post-recovery janitor inventory reported zero source-edit locks and zero
active/corrupt/expired leases. `desktop_safe_patch_harness.ps1 -NoWrite`
reported block findings `0`, high-confidence secret hits `0`, active root
source-set ownership, and only LangChain4j `1.0.1` declarations. Java is
`17.0.13`.

## Ranks 22-23: AutoEvolve directory-stream ownership

### Owner and root cause

- production owner:
  `main/java/com/example/lms/scheduler/AutoEvolveDebugStore.java`
- focused test:
  `src/test/java/com/example/lms/scheduler/AutoEvolveDebugStoreRedactionTest.java`
- active callers:
  `TrainingJobRunner -> AutoEvolveDebugStore.record`, plus startup construction
  and internal AutoEvolve status/history reads
- rank 22 root cause: `cleanupOldRotations` consumed `Files.list(persistDir)`
  without owning/closing the returned stream
- rank 23 root cause: `loadRecentNdjson` repeated the same leak during enabled
  startup persistence restore

The source owner and focused test were clean at intake. A behavior candidate
based on moving the listed directory passed with the old implementation and
was removed rather than misreported as RED. The repository directive required
the exact directory-stream ownership invariant, so two focused ownership tests
were written and observed failing before production changed.

### Minimal patch and proof

- both directory listings now use try-with-resources
- no persistence format, retention count, redaction, route, property, or caller
  contract changed
- focused suite: `6/6` GREEN after exactly two new RED failures
- affected AutoEvolve suites: `2` suites / `10` tests, zero failures/errors/skips
- production postimage SHA-256:
  `CD6DF44351F47D6477F1DD24546CCDF9E8F666147839C083F77FF7F9CF4E7E52`
- test postimage SHA-256:
  `E4699FD3DBF18559E654D778149176B56271D166C19FE476DDFAEE6DFF403AC6`

The source-edit preflight and owner lease completed before mutation in the
preceding goal turn. Its ephemeral EvidenceSnapshot hash was not retained
through task compaction, so this journal does not invent a replacement value;
the preimage, RED/GREEN outputs, postimages, lease release, and final integrated
verification are retained here.

## Ranks 24-25: bounded terminal state and owned executor lifecycle

### Fresh owner and preimage proof

- production owner:
  `main/java/com/example/lms/jobs/InMemoryJobService.java`
- focused test:
  `src/test/java/com/example/lms/jobs/InMemoryJobServiceTraceContractTest.java`
- active wiring:
  `JobConfig -> JobService -> TasksApiController/N8nWebhookController`
- production preimage SHA-256:
  `838869382E3B0AC6C38485594E9ABB4387B70C16E8B17177B2DECEA106856CAA`
- focused-test preimage SHA-256:
  `2E4AFFBF0566F8DCEEE011C8F3F2DC0FDE87F748D0C667E034274D24F67EA052`
- existing dirty source hunk: raw payload-map removal and null rejection only
- existing dirty test hunk: payload non-retention/null ghost-state tests only
- overlap with rank-24/25 seams: `false`
- untracked `JobService.java` and `JobConfig.java` were declared read-only and
  remained byte-identical; their final SHA-256 values are respectively
  `D6DDC4D2D56EE6DED6E8B9F25D1BA2318614907E4F571EE5DE8292E01491FFCF`
  and `7DC1A79CDA82084388B9824856D7F58F9BAB21A5F9F628082276545D89AD1674`

### EvidenceSnapshot and preflight

- evidence row count: `18`
- evidenceSnapshotHash:
  `71CFF2BD60DA73F32F0839ACE7EAE22C7E3D4FCE0EA2AC4966AEB5AB55FD81BE`
- process mode: `single-agent-logical-roles`
- canonical query count: `3`
- shared scenario IDs:
  `S24_TERMINAL_RETENTION,S25_EXECUTOR_LIFECYCLE,SCOPE_PRESERVATION`
- Positive/Negative scenario coverage equal: `true`
- forward/reverse verdict: `APPLY/APPLY`
- decisive evidence sets equal: `true`
- order stable: `true`
- goal score: `82.5`
- next workflow: `existing-source-owner-guard`
- lease topic / owner:
  `priority100-rank24-25` / `codex-root-01a03702-rank24-25`

POSITIVE required an oldest-terminal eviction at capacity plus one, bounded
admission/rejection, in-flight Spring shutdown, and exact preservation of the
pre-existing privacy hunks. NEGATIVE attacked polling loss, ghost RUNNING state,
callback/shutdown races, CRLF churn, and mutation of the untracked wiring.
NEUTRAL selected terminal-only conditional eviction, a bounded owner-local
executor, and no wiring/interface change.

### Deterministic RED and minimal patch

The baseline focused suite was `3/3`. After adding three behavior tests, the
same command reported `6` tests with exactly `3` expected failures:

- `257` completed jobs remained queryable instead of only `256`;
- `100` blocked jobs were accepted by the cached pool without rejection;
- closing the real Spring `JobConfig` context did not interrupt in-flight work.

The production patch:

- retains the newest `256` terminal records under one short lock;
- conditionally removes the exact oldest `jobId/state` mapping, preserving
  unrelated PENDING/RUNNING and replacement state;
- replaces the cached pool with a context-aware `ThreadPoolExecutor`: core `2`,
  max `4`, queue `64`, keep-alive `30s`, daemon factory, `AbortPolicy`;
- converts rejected jobs from RUNNING to FAILED, records only fixed/redacted
  breadcrumbs, and rethrows `RejectedExecutionException`;
- adds idempotent Spring `@PreDestroy` shutdown, interrupts owned work, and
  fences remaining RUNNING entries to FAILED;
- does not change `JobService`, `JobConfig`, controller APIs, payload privacy,
  properties, dependencies, or external systems.

Focused GREEN is `6/6`. Job/controller affected proof is `4` suites / `21`
tests with zero failures/errors/skips.

- production postimage SHA-256:
  `49557D2092457097ABBF9761E34AA007D8F076BECE8EBE654FC30AD6F2214125`
- test postimage SHA-256:
  `D4D8B583097F0E918DE35A251D27E61203726108DEEC84DBCAFF6E977218777E`
- lease release: `source-edit-locks=0`

## Rank 26: stable ETA duration window

### Fresh owner and preimage proof

- production owner:
  `main/java/com/example/lms/plugin/image/jobs/ImageJobService.java`
- focused test:
  `src/test/java/com/example/lms/plugin/image/jobs/ImageJobServiceDebugDiagnosticsTest.java`
- source/test intake: tracked and clean
- production preimage SHA-256:
  `2872E2DF3EB6F7C2A15B2B6D8B7D4364E738E447DFD42EB9A31BBBACC1BD80AC`
- test preimage SHA-256:
  `26F14DFA2AE5089DC130C615CC316B607265AB607391831C47C5AB44B2A4F44C`
- reader path:
  `ImageGenerationPluginController/ImageJobDebugController -> estimate`
- writer path:
  scheduled `processNext -> finally -> recentDurations.addLast/removeFirst`

The reader streamed a raw `LinkedList` while the scheduler mutated the same
deque. No lock or snapshot existed.

### EvidenceSnapshot and preflight

- evidence row count: `15`
- evidenceSnapshotHash:
  `3C1EBE39454C736BAB13A1440F928FC759B903265C32DCC299D9BF7A62A78411`
- process mode: `single-agent-logical-roles`
- canonical query count: `3`
- shared scenario IDs:
  `S26_STABLE_SNAPSHOT,S26_SHORT_CRITICAL_SECTION,S26_REDACTION_SCOPE`
- forward/reverse verdict: `APPLY/APPLY`
- decisive evidence sets equal: `true`
- order stable: `true`
- goal score: `88.25`
- lease topic / owner:
  `priority100-rank26` / `codex-root-01a03702-rank26`

NEGATIVE explicitly rejected probabilistic stress loops, a lock around provider
or repository work, and mock-call-only assertions. The selected test uses a
real fail-fast `LinkedList` traversal whose consumer is paused by a latch. A
scheduled writer is proven to reach the second repository save and append
boundary before the reader is released.

The RED suite reported `4` tests with exactly one failure whose nested cause was
`ConcurrentModificationException`. The production patch adds one private lock,
copies a stable duration snapshot under that lock, and guards only scheduler
add+trim under the same lock. Repository, manifest, provider, storage, debug,
and average-calculation work remain outside the critical section.

- focused GREEN: `4/4`
- image affected proof: `6` suites / `35` tests, zero failures/errors/skips
- production postimage SHA-256:
  `8741CC8EB1F5F24118EE38C4B5BE2350BE4038EA3326ED4088DC66DD17F4A1FC`
- test postimage SHA-256:
  `BF1A9875EAAD3F052663F74A2FFE63541CA2629E10900718BFE4A96F1F244CE0`
- lease release: `source-edit-locks=0`

## Integrated verification

- combined affected command: the 12 named AutoEvolve, job/controller, image
  service/controller/debug/redaction suites under split host
  `desktop-rank22-26-final`
- combined affected result: `12` suites / `66` tests, failures/errors/skips
  `0/0/0`
- `compileJava`: `BUILD SUCCESSFUL`
- `:app:classes`: `BUILD SUCCESSFUL`
- `bootJar`: `BUILD SUCCESSFUL`
- `checkSourceSetHygiene`: `BUILD SUCCESSFUL`; the expected inactive-present
  `app/src/main/java` notice remains informational
- `checkLangchain4jVersionPurity`: `BUILD SUCCESSFUL`; declarations remain
  exactly `1.0.1`
- full command: `gradlew.bat test --rerun-tasks --fail-fast --no-daemon` with
  split build host and isolated project cache
- full result: `BUILD SUCCESSFUL in 3m 37s`
- XML aggregation: `1018` suites / `6494` tests / `0` failures / `0` errors /
  `3` skipped
- full XML result-manifest SHA-256:
  `866A937D919BD13BE6B05AF4DE5D854CC4E802C9813BBDE402FAE393244F875C`
- HTML report:
  `build/desktop-rank22-26-final/reports/tests/test/index.html`
- six declared source/test paths changed; staged paths `0`
- scoped `git diff --check`: exit `0`
- high-confidence secret-pattern hits: `0`
- `opnessl` occurrences: `0`
- trailing-whitespace lines: `0`

## Fresh task-owned runtime and Browser proof

After the source, affected, full-test, and JAR gates were already GREEN, the
existing listener harness started one isolated runtime with local-LLM startup
disabled. It used only task-owned ports `18086`, `18087`, and `18088` and did
not touch protected ports `11434`, `11435`, or `11438`.

- HTTP target status: `200`
- runIdHash:
  `5c7f496da9059e131ad547fa9e912d7a50669e9bf773bec4e437b13a4e5d45ce`
- source and served asset SHA-256, equal on both sides:
  `641188de64b47a353c56edc83c241bcd0f61b2ea8e709dac778fc9e49119ea4d`
- in-app Browser title: `AbandonWare AI`
- rendered landmarks: `Dynamic RAG workspace`, diagnostics, message textbox,
  and disabled empty-message Send button
- settled UI health: `Model proof unavailable / external proof needed`
- Browser warning/error console entries: `0`
- prompt submission count: `0`

The Browser proof therefore establishes fresh delivery and rendering only; it
does not establish a model/provider attempt. The Computer Use lane selected the
single returned Chrome window but was policy-stopped before any verified
navigation or input because the helper could not determine that window's
current URL confidently. No retry, URL typing, click, or other Windows UI
mutation followed.

Before cleanup, the runtime manifest matched the canonical root, run-id hash,
launcher/listener process identities, command hashes, creation times, parent
lineage, and listener port ownership. Verified cleanup stopped `8` task-owned
process-tree entries; remaining task-owned processes were `0`, and listener
counts on `18086/18087/18088` became `0/0/0`.

## Final preservation and evidence boundary

- canonical `.git/index.lock` present: `false`
- source-edit lease directories: `0`
- active top-level PatchDrop patches: `0`
- listeners on task-owned ports `18086`, `18087`, and `18088`: `0/0/0`
- no untracked wiring file was modified
- no unrelated worktree hunk was reset, cleaned, staged, normalized, or
  overwritten
- Browser delivery/rendering: freshly observed against the task-owned runtime
- Computer UI: `not_observed` after policy stop before any input
- provider/wire generation: `not_observed`; local and Browser GREEN are not
  promoted to a model-success claim

Superpowers was used as supporting TDD, systematic-debugging, and verification
process. Current source, repository gates, owner/preimage hashes, RED/GREEN
outputs, and Desktop final proof remained authoritative.
