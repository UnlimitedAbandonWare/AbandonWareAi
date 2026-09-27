# Exact cleanup request

Write this request under the existing task report directory after the patch
owner has finalized every required acceptance result. Values below illustrate
the schema; use actual files and hashes. Completion flags are the caller's
explicit reconciliation of the whole task, not a substitute for source evidence.

```json
{
  "schemaVersion": "awx.completed-directive-cleanup.v1",
  "deleteAuthorized": true,
  "allRequiredWorkComplete": true,
  "completionFlag": true,
  "queueRoots": ["data/agent-handoff/notebook"],
  "directive": {"path": "data/agent-handoff/notebook/selected.md", "sha256": "<current-intake-hash>"},
  "relatedMarkdown": [],
  "completionReport": {"path": "data/agent-handoff/codex/report/task/session.json", "sha256": "<final-report-hash>"},
  "postimages": [{"path": "scripts/changed.py", "sha256": "<verified-current-source-hash>"}],
  "evidenceFiles": [{"path": "data/agent-handoff/codex/report/task/green.log", "sha256": "<actual-verification-log-hash>"}]
}
```

Use `-Apply -MarkOnly` with this same request schema when retaining the
completed directive. `deleteAuthorized` remains a real boolean: marking accepts
`false`, while normal deletion requires `true`. Both modes require the complete
bound proof. Without `-Apply`, both modes are side-effect-free and write no
completion receipts.

All paths are exact root-relative paths. Queue roots select disposable locations;
they do not authorize every file below those locations. `relatedMarkdown` rows
have the same path/hash shape as `directive` and must belong to that completed
task. No globs or directory deletion. `.` is only for an explicitly selected
local folder root; do not use it to designate an entire drive for sweeping.

The bound final report must have `sourcePatchCompletion=verified`,
`desktopFinalProof=verified`, `directive` equal to the selected path, and
`postimages` matching the request's full source set. Existing report rows with
`path` + `postimage` are supported. `evidenceFiles` binds additional actual
acceptance files without publishing their bodies. The helper does not rerun
tests or upgrade a partial report into a final report.

Preserve the original receipt. A historical reconciliation records the current
directive hash, evidence references and exact requirement coverage in this
request's associated task report. If any required requirement is unresolved,
do not set allRequiredWorkComplete and leave the original directive in place.

If no usable historical final report exists, the current Desktop verification
owner may create a new current reconciliation report under
[the retention criteria](retention-review.md). Read all current requirements,
verify their source and required acceptance evidence, and bind current hashes;
do not infer historical execution or overwrite old receipts. Keep the five-class
retention ledger outside this strict request schema. Duplicate status alone does
not permit setting completion flags or using `relatedMarkdown` for pending work.

Logs must live outside the declared queue roots, except an explicit `.` local
root where the selected log subdirectory is excluded from targets. Recovery
files have `.bin` extension; final receipts and JSONL cleanup logs remain out of
Markdown candidate selection. An absent directive with a still-valid request
is idempotent cleanup, not proof that this invocation originally deleted it.

After read-only validation of the bound whole report, current readable directive
bytes, current source postimages and evidence hashes, the helper durably writes
`completion-status.json` and a short human-readable `COMPLETED.md` in the existing
task cleanup run directory before requesting DELETE access or attempting deletion.
They bind the exact original directive path/hash and final report and are derived
completion receipts, excluded from queue execution. Do not append to or rename
the original directive. Preserve its bytes and name until authorized deletion.

Result `directiveStatus=completed` is distinct from cleanup `status=complete`
or `hold`. Include `markedCount`, `retainedCount`, and receipt relative paths.
Mark-only retains the directive. Deletion failure leaves cleanup on hold while
the receipt still records completed work. Published `completion-status.json`
and `COMPLETED.md` remain immutable, with exact proof bindings and
`cleanupStateAtPublication=pending` or `retained`. The helper result and
`journal.finished` record final `cleanupState=deleted`, `cleanup-held`, or
`retained`; the receipt references that journal as authoritative for the cleanup
outcome, including interruption. Never rewrite these receipts after deletion.
A retry uses a separate run journal and preserves previous receipts. Retain
the initial receipt hashes to prove subsequent cleanup did not rewrite them.
The source owner report remains the completion authority. Failed initial
receipt publication or initial `completion-recorded` journal logging cannot
report a new successful durable completion mark. Any later journal failure
stops further deletion, reports cleanup `hold`, and preserves already verified
immutable receipts and completed state.

Missing required proof or unreadable current directive bytes yields
`evidence_needed` and no new marker. Already absent directives rely on their
existing bound completion evidence for idempotent cleanup; never claim absent
bytes were read. A stale marker cannot skip changed directive requirements or
broken proof: match the original path/hash, bound report, and current postimages
before skipping an unchanged verified patch. Retry cleanup only after its
reported condition changes. No queue-wide marking or watcher is introduced.

For multiple independent completed directives, use one request per final report.
Finish each once and retain its result. Do not merge unrelated completion flags
or choose a receipt by newest timestamp. Resolve stale queue pointers through
the exact bound completion receipts and journal. Skip only unchanged verified
requirements; completed cleanup failures never themselves requeue a source patch.
