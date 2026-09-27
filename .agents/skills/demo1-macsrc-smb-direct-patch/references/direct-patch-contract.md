# MacSrc SMB Direct Patch Contract

## Target-scoped concurrency

New sessions declare `coordinationMode=target-scoped` and
`verificationScope=declared-targets`. Watch roots establish coverage; they are
not exclusive reservations. Disjoint files can be edited by Desktop/Notebook
sessions concurrently. Completion and rollback check the declared target hashes,
not a whole-folder snapshot, so completed peer changes do not create false
undeclared-write or rollback failures. Keep task-only diff review; this mode does
not certify the absence or authorship of changes elsewhere in a watched folder.

The common registry rejects normalized target intersections and unknown/corrupt
scope, and briefly serializes admission with a bounded wait. No OS file handle
is retained during editing. An index lock is preserved and blocks index writes;
file-only operations use live Git-writer and preimage evidence. Unrelated pending
patches are accepted without a switch and re-evaluated by target intersection at
Verify/Complete. Existing sessions without the mode keep their original checks.
This section supersedes the legacy blanket conditions below.

## Authority and identity

The production root is `\\desktop-m5nov6k\MacSrc`. A mapped path is equivalent
only when PowerShell resolves its drive `DisplayRoot` to that UNC share. The
guard never writes global Git configuration and never treats OneDrive as a
fallback source root.

External reads, searches, tools, and temporary work remain unrestricted. The
guard controls only paths named in `TargetFiles` and writes its own lease/session
evidence inside MacSrc.

This contract assumes cooperative agents. It is not an OS access-control
boundary: a malicious administrator can race the gap between `Verify` and an
external patch command, and a caller can fabricate a syntactically valid
verification JSON. Use an isolated Desktop-local worktree or an
owner-controlled in-process patch/verifier when those adversaries are in scope.

## Commands

```powershell
Set-Location Y:\
$run = "notebook-<topic>-<unique-id>"
$owner = "notebook-<session-id>"

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-macsrc-smb-direct-patch\scripts\macsrc_smb_patch_guard.ps1 `
  -Mode Prepare -Root . -RunId $run -OwnerId $owner `
  -TargetFiles "main\java\com\example\lms\service\Example.java" `
  -BoundaryEvidenceFiles "build.gradle.kts" `
  -WatchRoots "main\java\com\example\lms\service"

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-macsrc-smb-direct-patch\scripts\macsrc_smb_patch_guard.ps1 `
  -Mode Verify -Root . -RunId $run -OwnerId $owner

# Apply only the declared source change with apply_patch, then run focused tests.
# Capture the real result in the session directory; do not pass an unattested
# exit-code argument to the guard.
& .\gradlew.bat compileJava
$verificationExitCode = $LASTEXITCODE
$sessionDir = Join-Path (Resolve-Path .).ProviderPath `
  "data\agent-handoff\macsrc-smb-direct\$run"
$sessionPath = Join-Path $sessionDir "session.json"
$target = "main\java\com\example\lms\service\Example.java"
$verification = [ordered]@{
  schemaVersion = "awx.macsrc_smb_patch_verification.v1"
  runId = $run
  sessionSha256 = (Get-FileHash -LiteralPath $sessionPath -Algorithm SHA256).Hash.ToLowerInvariant()
  exitCode = $verificationExitCode
  command = ".\gradlew.bat compileJava"
  targetPostimages = @([ordered]@{
    relativePath = $target
    sha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
  })
}
$evidencePath = Join-Path $sessionDir "verification.json"
[IO.File]::WriteAllText(
  $evidencePath,
  (($verification | ConvertTo-Json -Depth 8) + "`n"),
  [Text.UTF8Encoding]::new($false)
)

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-macsrc-smb-direct-patch\scripts\macsrc_smb_patch_guard.ps1 `
  -Mode Complete -Root . -RunId $run -OwnerId $owner `
  -VerificationEvidenceFile "data\agent-handoff\macsrc-smb-direct\$run\verification.json"
```

Use `-AllowDeletion` during `Prepare` only when the directive explicitly names a
file deletion. Prepare permits disjoint queued patches and rejects intersecting
or unparseable path inventories, including rename/copy/binary diff sections.
`-AllowPendingPatchQueue` is a compatibility acknowledgement, never an overlap bypass.
Use `-Mode Abort` only after restoring a failed target to its preimage.
If bytes differ, Abort returns `rollback-required` and deliberately retains the
lease. An expired but unchanged owner-bound lease remains safely abortable.

## Session output

The immutable `session.json` contains:

- canonical root identity and `MACSRC_SMB_DIRECT` mode;
- `externalReadAccess=unrestricted` and
  `applicationSourceWriteRootOnly=true`;
- declared target preimage SHA-256 values and relative paths only;
- boundary-evidence paths and hashes;
- explicit watch roots and their pre-edit manifest;
- a nonce that is bound to the lease and session checksum;
- Git evidence mode without native stderr;
- count-only secret policy and PatchDrop inventory hash.

State transitions are separate atomic records: `ready.json`,
`verification.json`, `completion.json`, `abort.json`, and `last-failure.json`.
`completion.json` stores the verified postimages. The session never trusts an
absolute path copied from a previous phase; every phase resolves its relative
paths again and rejects reparse points.
New target-scoped sessions verify and roll back only their declared targets;
`WatchRoots` prove coverage. Legacy sessions retain their recorded full-watch
behavior. Never restore a peer's independently owned sibling file.

## Failure classifications

| Class | Meaning / response |
| --- | --- |
| `macsrc-root-mismatch` | Stop; prove the actual MacSrc UNC/mapped-drive identity. |
| `target-outside-macsrc` | Stop; replace the target with a relative in-root path. |
| `target-outside-watch-root` | Stop; declare the actual narrow source boundary. |
| `reparse-path-risk` | Stop; use a non-link path inside the proven root. |
| `boundary-evidence-invalid` | Stop; provide a current repo file proving the source boundary. |
| `index-lock-present` | Legacy classification; current declared file-only operations inspect actual Git writers and preserve the index lock. |
| `source-lease-blocked` | Stop; another source edit or corrupt lease owns the surface. |
| `patch-drop-pending` | Review the queued patch; acknowledge only a proven non-overlap. |
| `patch-drop-queue-changed` | Re-prepare after the queue reaches a stable reviewed state. |
| `patch-drop-path-inventory-unverified` | Repair or remove the nonstandard pending patch before direct mode. |
| `patch-drop-overlap` | Do not combine the direct session with a queued patch touching its declared targets. |
| `preimage-changed` | Re-prepare from the new source bytes. |
| `boundary-evidence-changed` | Re-prove the active boundary and re-prepare. |
| `session-integrity-mismatch` | Stop; preserve the tampered evidence and prove ownership before recovery. |
| `undeclared-source-change` | Restore the undeclared write or prepare a directive that declares it. |
| `secret-leak-risk` | Roll back the source change; the lease remains held. |
| `verification-evidence-missing` | Run the real check and emit the repo-local evidence record. |
| `verification-evidence-invalid` | Fix the schema/run/session binding; do not release the lease. |
| `verification-target-hash-mismatch` | Re-run verification against the current target bytes. |
| `rollback-required` | Restore the declared targets before retrying Abort. |
| `unapproved-target-deletion` | Restore the file or re-prepare with explicit deletion approval. |

## Unknown future layouts

Do not hard-code only today's Java roots. A target is eligible when it is inside
the proven MacSrc root and the caller supplies a current in-repo boundary file
such as `build.gradle.kts`, `settings.gradle.kts`, `package.json`, or another
authoritative build/source manifest. This keeps access broad while making the
actual write set explicit and checksum-bound. `WatchRoots` are caller-proved
boundaries rather than a hard-coded list, so a future source layout can be used
without weakening the MacSrc root invariant.

## Renewal and recovery

Use `-Mode Heartbeat -Root Y:\ -RunId $run -OwnerId $owner` at progress
boundaries. It preserves the immutable session and lease hashes; renewal itself
authorizes no patch. `Prepare -OwnerProcessId <persistent-supervisor-pid>` enables
validated local owner recovery. Never pass the transient per-command helper PID.
The shared [lease lifecycle](../../scoped-blocker-recovery/references/lease-lifecycle.md)
owns process identity, prefix reservations, recovery receipts and debug fields.
Expiry or a 24-hour-old directory never authorizes deleting a live, remote or
unknown owner. Automatic recovery retains the exact lease in quarantine.
