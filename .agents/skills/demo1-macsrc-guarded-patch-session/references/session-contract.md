# Guarded Session Contract

## RED Evidence

Store a UTF-8 `awx.red-evidence.v1` JSON record inside MacSrc with:

```json
{
  "schemaVersion": "awx.red-evidence.v1",
  "runId": "<intent-run-id>",
  "intentSha256": "<intent-json-sha256>",
  "command": "<exact-intent-red-command>",
  "exitCode": 1,
  "expectedSignalMatched": true,
  "outputSha256": "<redacted-output-sha256>"
}
```

Do not include raw output. A zero exit, a different command, a false signal
match, or an intent hash mismatch is not RED.
The planner reads each intent and RED file once under a read lock and derives
both JSON and SHA-256 from that same byte snapshot. A later replacement cannot
be blessed by the stored plan hash.

## Session Plan

`new_guard_session_plan.ps1` writes `awx.guard-session-plan.v1` under:

```text
data/agent-handoff/macsrc-guarded-patch/<runId>/plan.json
```

Consumers require matching `.sha256` and `.ready` files. The plan always has
`mutationAllowed=false`. `READY_FOR_GUARD` means only that current evidence is
eligible for the existing guard's own Prepare checks.
Mapped drives are normalized through `PSDrive.DisplayRoot`; readiness requires
the normalized root to equal `\\desktop-m5nov6k\MacSrc` case-insensitively.
Any temporary/local/mirror root is `HOLD: macsrc-root-mismatch`, even with
valid RED. A Git `dubious ownership` result on the exact share selects
`gitEvidenceMode=filesystem-cas`; it is not permission to change global
`safe.directory` or copy source elsewhere. Target and boundary preimage hashes,
operation-specific Git, target preimage, PatchDrop, lease, reparse, and guard checks remain mandatory. Intersecting or unknown scopes hold the affected edit; disjoint sessions and disjoint queued patches may proceed. An index lock alone does not block a verified index-independent edit. Plan
output is restricted to the guarded-patch handoff root; rooted, traversal,
reparse, and alternate data stream (`:`) paths fail closed.

## Exact Guard Sequence

Read the intent only after verifying its ready marker and SHA-256. Use its
arrays directly; do not retype paths from memory.

```powershell
$guard = '.\.agents\skills\demo1-macsrc-smb-direct-patch\scripts\macsrc_smb_patch_guard.ps1'

powershell -NoProfile -ExecutionPolicy Bypass -File $guard `
  -Mode Prepare -Root . -RunId <run-id> -OwnerId <stable-owner-id> `
  -TargetFiles <intent-targets> -WatchRoots <intent-watch-roots> `
  -BoundaryEvidenceFiles <intent-boundary-files>

powershell -NoProfile -ExecutionPolicy Bypass -File $guard `
  -Mode Verify -Root . -RunId <run-id> -OwnerId <same-owner-id>

# Apply one minimal patch to declared targets, then run intent.green.command.

powershell -NoProfile -ExecutionPolicy Bypass -File $guard `
  -Mode Complete -Root . -RunId <run-id> -OwnerId <same-owner-id> `
  -VerificationEvidenceFile <relative-verification-json>
```

Use `-Mode Abort` with the same root, run ID, and owner ID after failure or
interruption. Never use `-AllowPendingPatchQueue` from this orchestration skill;
queue reconciliation belongs to the source owner before a new session plan.

## Verification Evidence

The existing guard contract is authoritative. Bind the real GREEN command,
exit code, immutable session SHA-256, run/owner identity, and every declared
target postimage hash. Record counts, hashes, timing, and reason codes only.
Successful Complete releases the lease; required rollback keeps it held.
