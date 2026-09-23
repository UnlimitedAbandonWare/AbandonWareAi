---
name: demo1-macsrc-smb-direct-patch
description: Use when a Notebook or agent is explicitly authorized to patch application source
---

# Demo1 Y-Drive SMB Direct Patch

## Concurrent sessions

New sessions use `coordinationMode=target-scoped` and
`verificationScope=declared-targets`. Disjoint Desktop and Notebook target files
may share a checkout and WatchRoot. The existing registry reserves only target
paths; its short publication handle waits briefly and is released before editing.
Verify rechecks the current target preimages, path conflicts and boundaries.
Complete and Abort assess only this session's target hashes, including when a
peer has already finished. Whole-folder changes cannot establish who wrote them;
do not call target-scoped results whole-source or undeclared-write certification.
Sessions created before this mode retain their recorded broad-watch checks.

An index lock alone does not block these index-independent edits. Actual Git
writers and merge/rebase state remain held. Unrelated pending patches are allowed
automatically; intersecting or unparseable paths remain held. These rules replace
the older blanket lease/index/watch-root wording below; root identity, owner,
nonce/session binding, preimages, secret scans and verification evidence remain.

The canonical Notebook workspace and persistent source-write root is `Y:\`.
Verify its current `DisplayRoot` against the expected backing-share identity;
the UNC value is evidence, not a replacement path.

## Core Boundary

Keep reads, searches, web use, tools, and evidence collection unrestricted.
Constrain only persistent application-source writes: guarded direct work writes
under the proven canonical `Y:\` workspace. Record the current `DisplayRoot`
separately as `backingShareIdentity` and compare it with the expected backing
share `\\desktop-m5nov6k\MacSrc`; never rewrite the workspace or write root to
that UNC value.

Do not redirect source or its patch traces to OneDrive. An explicit user request
may select another path for non-source output. A different application-source
root requires a separate explicit user decision; this skill does not authorize
it.

## Select the Mode

| Evidence | Mode |
| --- | --- |
| Direct source edit is explicitly authorized and every direct gate passes | `YDRIVE_SMB_GUARDED_DIRECT` |
| Legacy input requests `MACSRC_SMB_DIRECT` and every direct gate passes | normalize and emit `YDRIVE_SMB_GUARDED_DIRECT` |
| User requests audit, plan, or directive only | `AUDIT_ONLY` |
| Mapping identity, guard, target boundary, index state, or verification is unproven | `HOLD` |

`MACSRC_SMB_DIRECT` is a historical compatibility input only; never emit it as
the successful mode. In any decision JSON, normalize that input before output:
set `mode` to `YDRIVE_SMB_GUARDED_DIRECT` only when every direct gate passes.
Failed direct gates return `HOLD` with the applicable existing failure
classification, authorize no source write, and select no fallback workspace.

Git `dubious ownership` does not force a local or OneDrive copy. Never change
global `safe.directory`. Use the guard's preimage checksum and shared source
lease fallback.

## Run the Guarded Loop

Read `references/direct-patch-contract.md` before the first direct patch.

1. Prove the active sourceSet or call boundary with current repo files.
2. Start at `Y:\` and run the historical
   `scripts/macsrc_smb_patch_guard.ps1 -Mode Prepare` with a relative declared
   target, repo-relative boundary evidence, and the narrowest practical
   `-WatchRoots`. Use the complete example in
   [the direct patch contract](references/direct-patch-contract.md#commands).
   When a persistent supervising process is proven, pass `-OwnerProcessId`;
   renew with `-Mode Heartbeat` at progress boundaries. See the shared
   [lease lifetime contract](../scoped-blocker-recovery/references/lease-lifecycle.md).
   Prepare quarantines only owners whose local PID/start identity proves exit.
   Expiry, remote PID and directory age alone never authorize recovery. Retained
   receipts are recovery evidence, not patch success.
3. Run `-Mode Verify` immediately before `apply_patch`.
4. Apply the smallest patch only to the declared relative targets beneath `Y:\`.
5. Run the focused RED/GREEN verification.
6. Write repo-relative verification evidence under
   `data/agent-handoff/macsrc-smb-direct/<runId>/verification.json`. Bind it to
   `runId`, the immutable `session.json` SHA-256, the real command and exit
   code, and every declared target postimage hash.
7. Run `-Mode Complete -VerificationEvidenceFile
   data/agent-handoff/macsrc-smb-direct/<runId>/verification.json`. It rejects
   undeclared writes or mismatched evidence, records postimage hashes, and
   releases the historical lease only on success.
8. On interruption or failure, restore the intended preimage when needed, then
   run `-Mode Abort`. Abort keeps the lease while rollback is still required.

Store the historical trace identifier under
`Y:\data\agent-handoff\macsrc-smb-direct/<runId>/session.json`. Reuse the
existing `__patch_drop__/source-edit-locks` historical lease contract so
PatchDrop and direct-edit sessions cannot silently overlap.

## Safety Gates

- Accept only relative targets whose normalized paths remain inside `Y:\` and
  whose paths are covered by an explicit watched source boundary.
- Reject reparse traversal, actual conflicting Git writers, changed target or
  boundary preimages, intersecting or unknown lease scopes, session tampering
  and secret hits. An index lock alone does not block verified file-only edits.
  Preserve live, remote and corrupt locks; never infer abandonment from age.
- Reject intersecting or unparseable top-level PatchDrop paths. Disjoint queued
  patches do not block declared targets. `-AllowPendingPatchQueue` is retained
  as a compatibility acknowledgement and does not bypass path checks. The guard requires and
  binds every standard `diff --git` section path, including rename/copy/binary
  sections; a quoted/nonstandard, partially parsed, or overlapping queue fails
  closed even when the switch is present.
- Allow dirty worktrees and unavailable Git metadata only through hash-based
  compare-and-swap evidence; record `gitEvidenceMode=filesystem-cas`.
- Keep public API, DB, credentials, deployment, commit, and push outside this
  authorization unless the user separately approves them.
- Treat Browser, Computer, Notebook, and SMB verification as supporting evidence
  when Desktop/runtime final proof is still required.

## Flexibility Rule

Do not turn this source-write guard into an access sandbox. New source layouts
are allowed when a current repo file is supplied as boundary evidence. Temporary
files may use the OS temp directory. Non-source output may use another explicit
user-selected path. Only the application-source write target is `Y:\`-scoped.

## Threat Model

This is a cooperative agent/owner lease and compare-and-swap protocol, not an OS
write sandbox. It checks reparse traversal before each phase and detects
post-write drift, but an administrator or malicious concurrent writer could
replace a path after `Verify` and before the external `apply_patch`. The
verification record is checksum-bound evidence, not an authenticated build
attestation. If hostile concurrent writers or untrusted evidence producers are
in scope, use a Desktop-local isolated worktree or an owner-controlled patch
runner that performs mutation and verification inside one OS-enforced boundary;
do not claim this guarded SMB-direct lane closes that threat.

## Red Flags

- Choosing OneDrive because it is locally writable
- Rewriting canonical `Y:\` to a UNC path because it appears more direct
- Blocking external reads or tools to enforce a source-write rule
- Mutating global Git trust configuration
- Patching an undeclared target after `Verify`
- Supplying an exit code without a hash-bound repo-local verification record
- Treating `Prepare` as proof that tests passed

Any red flag changes the source-mutation verdict to `HOLD`.
