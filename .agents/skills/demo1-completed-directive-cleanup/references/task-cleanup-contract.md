# Verified task completion and surplus cleanup

The existing cleanup engine also accepts `awx.completed-task-cleanup.v1`.
All references are exact repository-relative `path` + lowercase SHA-256 pairs.
No command in the report or request is executed. Default invocation is dry-run;
the already authorized finalization hook passes `-Apply` automatically.

## Required request

```json
{
  "schemaVersion": "awx.completed-task-cleanup.v1",
  "taskId": "selected-task",
  "taskKind": "patch",
  "deleteAuthorized": true,
  "allRequiredWorkComplete": true,
  "completionFlag": true,
  "completionReport": {"path": "data/agent-handoff/selected-task/final/completion.json", "sha256": "<actual hash>"},
  "postimages": [{"path": "scripts/changed.py", "sha256": "<verified current hash>"}],
  "evidenceFiles": [{"path": "data/agent-handoff/selected-task/final/verify.log", "sha256": "<actual log hash>"}],
  "preserveFiles": [
    {"path": "data/agent-handoff/selected-task/final/report.md", "sha256": "<actual hash>"},
    {"path": "data/agent-handoff/selected-task/final/applied.patch", "sha256": "<actual hash>"},
    {"path": "data/agent-handoff/selected-task/recovery/preimage.bin", "sha256": "<actual hash>"}
  ],
  "queueRoots": ["data/agent-handoff/selected-task/work"],
  "disposableArtifacts": [
    {"path": "data/agent-handoff/selected-task/work/old.patch", "sha256": "<actual hash>", "kind": "superseded-patch", "retainedPath": "data/agent-handoff/selected-task/final/applied.patch"},
    {"path": "data/agent-handoff/selected-task/work/draft.tmp", "sha256": "<actual hash>", "kind": "intermediate", "retainedPath": "data/agent-handoff/selected-task/final/report.md"},
    {"path": "data/agent-handoff/selected-task/work/copy.report.md", "sha256": "<same hash as retained report>", "kind": "duplicate-report", "retainedPath": "data/agent-handoff/selected-task/final/report.md"}
  ]
}
```

The hash-bound completion report must repeat `taskId`, `taskKind`, both true
completion booleans, the full `postimages`, `evidenceFiles`, `preserveFiles`,
`queueRoots` and `disposableArtifacts` sets. It also contains:

```json
{
  "sourcePatchCompletion": "verified",
  "desktopFinalProof": "verified",
  "appliedPatch": {"path": "data/agent-handoff/selected-task/final/applied.patch", "sha256": "<actual hash>"},
  "acceptance": [
    {"id": "required-focused-tests", "status": "passed", "exitCode": 0,
     "evidence": {"path": "data/agent-handoff/selected-task/final/verify.log", "sha256": "<actual log hash>"}}
  ]
}
```

Every required acceptance item must appear, including any required runtime or
external proof. Each log must exist, be nonempty and match its pinned hash.
The verification owner is responsible for truthful requirement coverage; this
engine verifies bindings and current bytes, and does not infer semantic success
by searching arbitrary log text for `PASS`. Missing external proof prevents
completion only when required by this task.

`completionReport` is a separate machine-readable binding record, not a
postimage of itself: a record cannot contain its own SHA-256. Bind the actual
final deliverable (for example `final/report.md`) in `postimages` and retain
`final/completion.json` as the authority record. These paths must be distinct.

For `taskKind=report`, use `reportCompletion=verified`, place the final report
artifacts in `postimages`, and omit source/Desktop proof fields. This mode is
only for a task whose entire objective is a report; never relabel an unfinished
patch as report-only. It cannot delete `temporary-patch` or `superseded-patch`
artifacts. Empty disposable lists are supported to record completion without
deleting anything. Keep at least one postimage and acceptance evidence file.

## Candidate and preservation rules

- Allowed task artifact locations are strict descendants of
  `data/agent-handoff/<explicit-subroot>/`, or consumer-reconciled
  `__patch_drop__/superseded/<explicit-subroot>/` and
  `__patch_drop__/applied/<explicit-subroot>/`. The owner binds exact roots and
  files in the report. Active top-level patches, producer bundles and their
  metadata stay with the existing PatchDrop consumer. This helper never discovers
  candidates by recency, scans a queue, or changes source authority.
- `temporary-patch` / `superseded-patch`: `.patch` files, patch task only, with
  `retainedPath` equal to the report's hash-bound `appliedPatch`, kept in the
  protected reference set. The final applied patch is always preserved.
- `duplicate-report`: `.md`, `.txt`, or `.json`, byte-identical to a preserved
  report. Do not discard unique findings from old reports based on similar names.
- `intermediate`: only `.tmp`, `.temp`, `.scratch`, or `.draft`, owned by this
  completed task and with a retained final replacement.
- Source/original/final/evidence/recovery/backup/receipt/lock/secret locations,
  verification `.log`, `.verify.log`, `.bin`, `.sha256`, bundle manifests,
  checkpoint manifests and journals are protected. The completion report,
  postimages, evidence, preserved files, request and cleanup output cannot overlap
  candidates. The engine also rejects aliases, traversal, reparse points and
  hardlinks. It holds evidence against concurrent writes/deletes on Windows.

## Invocation and lifecycle

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-completed-directive-cleanup/scripts/cleanup_completed_directives.ps1 `
  -Root . -RequestPath <exact-task-request.json> -LogDirectory <task-cleanup-output>
# The authorized finalization caller uses the same arguments with -Apply.
```

`-ExpectedRequestSha256 <pinned hash>` binds a caller's already checked request
before any cleanup. The checkpoint hook always supplies it. It detects exactly
`<checkpoint-cycle>/task-cleanup-request.json` on successful `finish`, verifies
the goal ID and inclusion of every sealed checkpoint postimage, and invokes the
engine once with a 150-second child timeout. No request means the checkpoint
remains an intermediate step. Failed verification never calls cleanup. For a
final report produced after `finish`, run the helper directly; do not replay
checkpoint verification merely to trigger cleanup.

`completion-status.json` and `COMPLETED.md` are durable immutable receipts.
`taskStatus=completed`, `stopWork=true` and `doNotReapply=true` mean the owner
has bound whole-task proof; consumers must still revalidate it before skipping
later work. `cleanupState` is separate: `deleted`, `retained`, or `cleanup-held`.
Before each deletion, the engine retains and verifies a `.bin` recovery copy
and flushes `delete-planned` into `journal.jsonl`. Restore only the exact recorded
destination from a matching recovery hash under the existing recovery policy.

Deletion failure leaves task completion intact. A timeout or malformed output
causes the checkpoint to request receipt reconciliation and never retries source
work or blindly repeats cleanup. Original checkpoint verification stays `verified`.
There is no watcher, inferred process kill, remote publication, or UI archival in
the helper. The skill closes the current work loop and uses goal/archive tools
only within their actual task authority.

Bounds: 512 file references, 1 MiB per file, 120-second cooperative deadline;
metadata-only output and per-item recovery. Larger logs/artifacts remain held
for their existing owner; they are never silently ignored or deleted.
