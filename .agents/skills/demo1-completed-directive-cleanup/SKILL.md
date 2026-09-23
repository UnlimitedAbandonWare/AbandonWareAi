---
name: demo1-completed-directive-cleanup
description: Use when a verified patch or report task should automatically stop
---

# Completed Directive Cleanup

## Automatic task finalization

For a whole completed patch or report-only task, use the existing helper's
`awx.completed-task-cleanup.v1` request described in
[task-cleanup-contract.md](references/task-cleanup-contract.md). The user's
2026-09-15 request authorizes this finalization and cleanup of proven surplus;
no additional routine confirmation is needed. This is a completion hook in the
active task, not a periodic scan of other tasks or files.

The final verification owner supplies the exact task ID, required acceptance
results with actual logs, current postimages, final report and recovery files,
and each disposable artifact's current hash and retained replacement. Patch
tasks require actual application plus whole-task Desktop proof; report-only
tasks require final report verification and claim no source patch success.
One successful test, an unfinished patch's analysis report, file age, and a
filename containing `done` never establish whole-task completion.

When using a checkpoint, write `task-cleanup-request.json` in that exact cycle
after the final report is complete and before calling `finish`. A successful
`finish` automatically detects it, binds it to the checkpoint goal ID and
postimages, and invokes cleanup once with its pinned request hash. Other verified
intermediate checkpoints continue normally. Without a checkpoint, the final
owner invokes the same helper directly with the v1 task request. Do not create
a checkpoint merely to run artifact cleanup.

The helper publishes `taskStatus=completed` and `stopWork=true` before deleting.
Keep `status=verified` for checkpoint proof separate from task and cleanup states.
On cleanup failure, stop completed patch/report work and resume only cleanup
after the reported condition changes. Revalidate the receipt, report and current
postimages before using an old completion marker to skip work. Never retry the
patch to address a cleanup failure.

After publishing the final user-facing report and required evidence, end the
current work loop. Mark the active Codex goal complete only if its entire objective
is satisfied. If task archiving is part of the current user's authorized completion
request, use the available `set_thread_archived` tool for that exact task; retain
the result in the report. Local `stopWork` is a consumer signal, not proof of
Codex UI archival or process termination. Release only owned leases/children by
their existing lifecycle helpers; never kill a process inferred from age, a
filename, or a reused PID. Other tasks require their own bound completion proof.

Preserve original source, the applied final patch, final reports, verification
logs, receipts, manifests and recovery bytes. Duplicate reports require exact
byte equality with a retained report. A unique older report is retained. Obsolete
patches require the preserved applied patch binding. Temporary work is limited
to explicitly listed `.tmp`, `.temp`, `.scratch`, and `.draft` files. No recursive
deletion, broad glob, active PatchDrop bundle deletion, or cleanup of other tasks.

Run `scripts/test_cleanup_completed_tasks.ps1` alongside the legacy directive
tests and `python -B -m unittest discover -s scripts -p test_codex_completion_cleanup_hook.py`.
The existing standalone-directive contract below remains supported unchanged.

Mark completed disposable Markdown instructions visibly, then remove them from
their queue when deletion is authorized and available, after the existing patch
owner has verified every required acceptance item. The user
authorized cleanup, its completion hook, and visible completion fallback on
2026-09-12; do not
ask again for the same verified files. Later narrower user instructions prevail.
This skill does not patch source or infer completion from a filename or age.

## Accumulated directive review

For an explicitly authorized backlog cleanup, use
[oldest-first retention criteria](references/retention-review.md). Inventory the
declared document roots, then classify each item as completed, duplicate,
unimplemented, held, or reference-needed. Keep classification separate from
deletion eligibility. An unreadable oldest file holds only that item; continue
with independent candidates in order. Review artifacts and source/configuration
have separate owners. Never mark a whole queue completed.

A missing historical final report is not a permanent cleanup veto: the current
Desktop verification owner may publish a new, explicitly dated reconciliation
report after reading every requirement and verifying it against current source,
required tests and any directive-required build/runtime/browser/provider/hardware
proof. Do not fabricate past execution, patch authorship, a commit,
or missing live proof. The existing helper still requires the complete report,
current hashes, durable receipts, recovery bytes, and deletion authorization.

## Completion boundary

1. At intake, remember the exact directive path and SHA-256 of the bytes read.
   Use current source and the existing owner/verification workflow. At the end,
   retain the final report, acceptance results, source postimages and rollback.
2. Require whole-directive completion: `sourcePatchCompletion=verified` and
   `desktopFinalProof=verified` in the actual source owner's final JSON report,
   an exact `directive` path, all required work done, and current postimages.
   A bare `APPLY`, `COMPLETE`, passed subset, producer receipt, or a completed
   analysis report is insufficient. Missing runtime proof holds deletion only
   when that runtime proof is required by this directive.
3. For historical directives without an intake hash or usable final report,
   inspect every original requirement against current source and acceptance
   evidence. Publish a fresh reconciliation report under the current task using
   the retention criteria, or bind still-valid existing final evidence. Bind
   current bytes explicitly; never fabricate a historical hash, backdate proof,
   or overwrite another task's completion report.
4. Build one exact cleanup request in the existing task report directory using
   [the request contract](references/request-contract.md). Include every related
   disposable `.md` explicitly. Do not recursively delete a directory.
5. Run the helper first without `-Apply`, inspect its exact candidate/reason
   result, then run with `-Apply` under the standing authorization. When called
   by an already prepared end hook, the helper performs its own pre-delete
   checks and durable plan logging, so a second human approval is unnecessary.
6. Record deleted/already-absent/held/failed separately. A deletion failure must
   not requeue a completed patch: keep it completed and retry only cleanup after
   the reported condition changes. On the next intake, consult the existing
   completion report and cleanup journal before reading a stale pointer. Never
   recreate a deleted directive merely because an old pointer references it.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-completed-directive-cleanup\scripts\cleanup_completed_directives.ps1 `
  -Root . -RequestPath data\agent-handoff\codex\report\<task>\cleanup-request.json `
  -LogDirectory data\agent-handoff\codex\report\<task>\cleanup -Apply
```

The helper validates paths, report, current directive and source hashes without
DELETE access before marking; separately guarded DELETE access follows only
for authorized deletion. It keeps write/delete sharing closed during validation,
rechecks the target before deletion, and flushes the exact planned list plus
completion receipts before removing any file. Recovery content uses `.bin` outside the queue,
so restoring a working queue entry is an explicit later action. Plain `.md`
copies are not left in a backup queue. Journal failure prevents further deletes.

## Visible completion and retention

- Use `-Apply -MarkOnly` to retain a verified directive and publish completion
  receipts. Normal deletion requires request `deleteAuthorized=true`; marking
  also accepts the real boolean `false`. Omitting `-Apply` is side-effect-free
  in both modes and writes no receipts.
- After the bound whole report, current readable directive bytes, postimages
  and evidence hashes pass read-only validation, durably write
  `completion-status.json` and human-readable `COMPLETED.md` in the existing
  task cleanup run directory before DELETE access or deletion. Preserve the
  original directive bytes and name. The new files are derived status receipts
  outside the queue, not executable instructions or a new completion authority.
- Result `directiveStatus=completed` is separate from cleanup `status=complete`
  or `hold`; include `markedCount`, `retainedCount`, and receipt relative paths.
  A deletion failure keeps cleanup on hold while the receipt records completed
  work. Published `completion-status.json` and `COMPLETED.md` are immutable,
  with `cleanupStateAtPublication=pending` or `retained` and exact proof bindings.
  The helper result and `journal.finished` record final
  `cleanupState=deleted`, `cleanup-held`, or `retained`; receipts reference that
  journal as the authoritative cleanup outcome, including interruption. Never
  rewrite a completion receipt after deletion. Retrying creates a separate run
  journal and preserves earlier receipts. Record initial receipt hashes so
  verification can prove subsequent cleanup did not rewrite them. Failed initial
  receipt publication or initial `completion-recorded` journal logging cannot
  report a new successful durable completion mark. Any later journal failure
  stops further deletion, reports cleanup `hold`, and preserves already verified
  immutable receipts and completed state.
- On the next intake, consult the exact original directive path/hash, bound
  final report, current source postimages, receipts and journal. Skip the patch
  only while unchanged requirements and proof remain verified. A stale marker
  never overrides changed bytes or broken proof. Retry cleanup only after its
  reported condition changes. Do not recreate an already deleted directive.
- Missing required proof or unreadable current directive bytes yields
  `evidence_needed` and no new marker. An already absent directive uses existing
  bound completion evidence for idempotent cleanup; never claim absent bytes
  were read. Mark one explicitly selected completed task, not a whole queue.
  No watcher or new queue registry is created.
- Falsifying tests cover readable read-only and delete-denied directives keeping
  completed receipts, unreadable/changed/partially proven directives receiving
  no new marker, dry-run writing nothing, and a stale marker never authorizing
  patch replay or silently skipping changed requirements.

## Existing pipeline integration

- Desktop canonical intake calls this skill immediately after final verified
  completion or a verified already-complete result, before selecting more work.
- The Docker wrapper accepts `-CompletedDirectiveRequest` together with
  `-CleanupLogDirectory` for a pre-reconciled final directive. It calls cleanup
  only after a successful `GREEN_VERIFICATION`; the helper still requires the
  whole final report. A RED probe, failed/empty grade, or ordinary `COMPLETE`
  cannot trigger deletion. If the final report is only produced after grading,
  call this helper from that finalization step instead.
- Standalone directives use this skill; sealed Canary and PatchDrop bundle
  records keep their existing consumers. The legacy consolidated program's
  disabled public retirement action is not activated or bypassed.

## Scope, budgets and recovery

- Owner: current authorized artifact cleanup caller. Root must be proven on
  that host; Notebook uses canonical `Y:\` after backing-identity verification.
  Shared access proves access to that share, not unshared Desktop disks.
- Mutation surface: exact disposable `.md` files and the chosen cleanup log
  directory. Preserve incomplete directives, source, SKILL/AGENTS, reusable
  prompts/references, actual test results, completion receipts and PatchDrop
  bundle metadata. Historical narrative reports are not queue files by default.
- Input/output: one bound request → separate directive/cleanup status, bounded
  counts, receipt relative paths, durable JSONL plan/result log,
  `completion-status.json`, and `COMPLETED.md`. No command in a directive or
  report is executed.
- Bounds: 512 file references, 1 MiB per read file, and a 120-second cooperative
  deadline checked between file operations. The grader hook adds a 150-second
  owned-child hard timeout for stalled native I/O.
  Root traversal, ADS, reparse/hardlink, changed evidence, incomplete proof or
  failed logging holds only this cleanup. No repeated unchanged retries.
- Redaction: logs contain relative paths, hashes and reasons; never raw file
  bodies, credentials, authorization headers or environment dumps.
- Rollback: restore an exact `.bin` recovery payload only on explicit request,
  after its recorded hash and destination are checked; do not requeue completed
  work automatically. Code rollback uses the task's baseline backups.
- Non-trigger: “delete all Markdown”, old timestamps, one test pass, read-only
  audit, unfinished requirements, template/example completion flags.
- Reuse rationale: the existing intake owns completion; the existing grader
  owns tests. This helper only marks or retires standalone instructions. The inventory
  curator and disabled consolidated retirement entrypoint do not implement it.
- Falsifiers: deleting a changed/unverified target, losing a plan log before
  deletion, treating partial proof as whole completion, following a reparse
  path, deleting reusable instructions, or reapplying the completed patch after
  a cleanup failure invalidates this skill.

Run `scripts/test_cleanup_completed_directives.ps1` and the Docker hook tests
for the affected boundary. Tests use isolated host-local fixtures; actual
Desktop execution remains separately evidenced.
