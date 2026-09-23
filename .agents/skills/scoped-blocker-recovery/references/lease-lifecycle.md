# Source reservation lifetime and artifact ownership

Read this contract when acquiring, renewing, diagnosing or recovering a source
reservation. The implementation owner is the existing
`__patch_drop__/source_edit_lease_contract.ps1`; Desktop and Notebook use it.
The registry coordinates cooperative writers, not OS access control.

## Resource and role boundaries

| Work | Coordination owner |
| --- | --- |
| Read, analysis, Markdown/directive planning | No application-source lease; unique task outputs |
| Desktop file edits | `source_edit_session.ps1` + nonempty target manifest |
| Notebook direct source edits | Historical Notebook guard; same target registry and Y-drive identity gates |
| Same file, different hunks | Analysis and drafting without a source lease may run concurrently; mutation serializes at the file target, and the later writer refreshes preimage/Verify after release |
| Directory-wide rename/generation | Optional `reservePaths` prefixes plus individual hashed actual targets |
| PatchDrop apply, Git index/ref, build, runtime | Their existing operation-specific owner; do not copy those gates into file edits |
| Generated AWX evidence intake | Topic-specific evidence directory (explicit `evidence_dir` stays exact); short OS handle on `.evidence-intake.lock`, no source lease |

`targets: [{path, sha256}]` authorizes only declared file preimages; `null` means a
new absent file. Optional `reservePaths: ["module/area"]` only expands collision
coverage. It does not authorize changing every child. Case, slash, dot and
parent/child aliases overlap. Sibling prefixes such as `area2` remain independent.
Unknown scope holds only the operation that cannot exclude overlap.

## Owner lifetime

New leases have a random `leaseId`, hashed task/owner/host identity and optional
owner PID plus exact process start time. Supply `-OwnerProcessId` only for the
long-lived local process supervising all writes. The helper verifies that this
process is its ancestor; it rejects itself, an unrelated PID or unavailable
ancestry evidence. Do not pass a per-command shell PID if edits outlive that
shell. Omit the option when a persistent supervisor cannot be proven: the lease
is usable through owner/fingerprint checks, but automatic process-death recovery
stays unavailable. Never replace this with a guessed remote PID.

Desktop example from a persistent supervising PowerShell:

```powershell
$task = 'unique-task-topic'
$owner = 'session-owner'
# This shell must remain alive for the complete editing session.
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
  -Action begin -Root . -Topic $task -OwnerId $owner -TaskId $task `
  -OwnerProcessId $PID -TargetManifest .\task-targets.json
# Read the returned leaseName; different owners can share the same topic.
$fingerprint = (Get-FileHash .\__patch_drop__\source-edit-locks\<returned-leaseName>\lease.json).Hash
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
  -Action heartbeat -Root . -Topic $task -OwnerId $owner -LeaseFingerprint $fingerprint -TtlMinutes 180
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
  -Action verify -Root . -Topic $task -OwnerId $owner -LeaseFingerprint $fingerprint -TargetManifest .\task-targets.json
# Apply only verified targets, check postimages and release in the caller's finally.
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
  -Action end -Root . -Topic $task -OwnerId $owner -LeaseFingerprint $fingerprint
```

Heartbeat is explicit at progress boundaries before TTL expiry (1–540 minutes),
not a new background watcher. A separate sidecar binds the exact lease ID and
fingerprint; renewal never rewrites lease bytes or Notebook `sessionSha256`.
An expired unknown reservation continues blocking overlaps, so its owner may
renew and then re-verify current targets without another owner entering the gap.
Wrong fingerprints, dead owners and foreign process lineages cannot renew a
process-bound lease. New generations cannot consume an old sidecar.

## Recovery and debug evidence

`begin` and Notebook `Prepare` automatically inspect abandoned reservations
under the short `.promotion.lock` OS handle. `-Action recover` performs the same
inspection explicitly. Only a same-host captured PID/start identity proved dead
(process absent or PID reused) permits recovery. Recheck bytes and inventory,
durably write a receipt, then move the exact directory into
`__patch_drop__/source-edit-quarantine/<id>/lease`. Preserve original bytes and
receipt; recovery never recursively deletes an unknown directory. Legacy,
remote, corrupt or uninspectable ownership stays evidence-needed. TTL or folder
mtime alone is never process-death proof. A live old directory is preserved.

`status -Json` is a read-only inventory. Human status and JSON show normalized
targets, hashed owner/task, PID/start, role, effective expiry, heartbeat age,
owner state and recovery reason. `status -TargetManifest` gives scoped conflict
counts. Events in `source-edit-events/<leaseId>.jsonl` record acquire, conflict,
heartbeat, recover and release, with bounded wait time and no raw owner token,
UNC root or process command. Heartbeat/event files are retained forensic
artifacts, not active leases; only the registry determines active ownership.

`.promotion.lock` and `.evidence-intake.lock` filenames may persist after normal
or abnormal exit. The OS handle is the lock and is released by the OS on process
exit; never delete a filename to break another process's handle. Same-output
evidence wrappers wait at most five seconds; different topic folders do not
share that handle. Direct evidence CLI callers sharing a chosen directory must
use the generated wrapper or hold that same artifact handle.

Run `test_source_lease_lifecycle.py`, `test_scoped_blocker_recovery.py`,
`test_concurrent_source_edit.py` and `test_evidence_dispatch_concurrency.py`
against temporary repositories. Local fixtures prove the coordination contract;
they are not fresh cross-host SMB or application runtime proof.
