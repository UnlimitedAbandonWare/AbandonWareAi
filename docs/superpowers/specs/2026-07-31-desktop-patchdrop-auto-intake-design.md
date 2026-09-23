# Desktop PatchDrop Automatic Intake Design

## Status and authority

The user delegated the design choice to the system. This specification selects
the safest design that can remove per-bundle Desktop commands without creating
an authority-bypass channel.

The Notebook may create and test repository-local tooling. It may not remotely
register a Desktop task, modify Desktop startup state, apply a canonical source
patch, create credentials, or claim Desktop proof. Installation and activation
remain Desktop-owned and `desktopFinalProof=evidence_needed`.

## Problem

Notebook-produced results currently require an explicit Desktop PatchDrop
consumer command. The desired behavior is automatic recognition and processing
after a one-time Desktop-owned installation, while retaining the existing
single-bundle, checksum, secret, source-isolation, lease, preimage, verification,
and rollback gates.

Leaving an unauthenticated trace that causes Desktop mutation is explicitly not
a supported mechanism.

## Considered approaches

### 1. Scheduled polling task — selected

A Desktop-owned Scheduled Task runs a bounded consumer at a fixed interval.
Polling is resilient to SMB event loss, naturally restarts after login/reboot,
and can be made single-instance and idempotent.

### 2. Codex project hook

A session or prompt hook could inspect PatchDrop whenever Codex processes a
command. It has a smaller operating-system footprint, but it is not background
automation, depends on project trust, and does not run while Codex is closed.

### 3. FileSystemWatcher service

A persistent watcher offers low latency but has a larger persistence surface and
must handle duplicated, reordered, or lost SMB notifications. The additional
complexity is unnecessary for patch handoff.

## Selected architecture

The package contains five independent units:

1. **Scanner** — inventories only top-level manifest-pinned cumulative v3
   candidates. It never selects by timestamp.
2. **Policy gate** — loads a Desktop-local policy and returns `OBSERVE`, `APPLY`,
   or `HOLD`. The repository sample policy defaults to `OBSERVE`.
3. **Consumer adapter** — invokes the existing repository janitor consumer by
   bare patch name only. It does not reimplement application or lease logic.
4. **Ledger** — writes exactly one bounded, append-only, redacted final outcome
   row per native run, after any adapter and verification result is known.
5. **Task manager** — renders or registers one exact disabled Desktop-owned
   Scheduled Task. Registration uses LocalSystem and canonical full-task identity
   readback; removal remains unavailable unless an atomic compare-and-delete
   primitive is separately proven. Notebook verification exercises render and
   temp-fixture modes; it does not register the real task.

## Operating modes

### OBSERVE — safe default

The task detects and validates candidates, records a redacted decision, and does
not invoke the apply adapter. This mode may be installed before producer trust
evidence exists.

### APPLY — explicit Desktop policy only

The adapter may run only when every condition below is true:

- a Desktop-local policy sets `mode=APPLY`;
- the canonical workspace and backing-share identity hashes match pinned values;
- the share ACL evidence is present and matches the Desktop-pinned ACL hash;
- exactly one complete top-level cumulative v3 bundle exists;
- the manifest node and topic are allowlisted;
- patch byte, changed-file, and hunk limits are within policy;
- no index lock, active conflicting lease, dirty target overlap, reparse
  traversal, changed preimage, or existing processed bundle hash exists;
- existing janitor manifest, source-isolation, SHA, secret, file-mode, dry-run,
  lease, apply, reverse-check, and rollback gates pass, followed by the policy's
  configured focused verification profile.

Any missing policy field or evidence returns `HOLD`. The repository tooling does
not create an ACL, signing key, certificate, credential, or trust entry.

## Inputs and outputs

### Policy input

```text
schemaVersion=awx.desktop-patchdrop-auto-intake.policy.v1
mode=OBSERVE|APPLY
enabled=true|false
expectedBackingShareIdentitySha256
expectedShareAclSha256
allowedNodes[]
allowedTopics[]
allowedPathPrefixes[]
maxPatchBytes
maxChangedFiles
maxHunks
pollIntervalMinutes
verificationProfile
```

The checked-in sample uses `enabled=false`, `mode=OBSERVE`, empty allowlists,
and no live identity or ACL value.

### Final ledger outcome

```text
schemaVersion=awx.desktop-patchdrop-auto-intake.outcome.v1
runIdHash
bundleIdHash
mode
decision=OBSERVE|APPLY|HOLD|REJECT|NOOP
failureClass
patchName
queueCount
secretPatternHitCount
leaseConflict
dirtyOverlap
preimageMatch
verificationPassed
rollbackReady
rollbackResult=READY|UNPROVEN|NOT_APPLICABLE|AMBIGUOUS
elapsedMs
desktopFinalProof
```

`patchName` is an additive, bounded field: it is empty when no candidate exists,
otherwise it is only the validated bare cumulative-v3 filename. It remains
subject to the bounded ledger-row byte cap and never contains a path. The native
result is a smaller machine-readable projection of this final row; no provisional
pre-adapter decision is appended.

No raw path mapping, UNC Git root, prompt, patch body, source snippet, secret,
environment dump, ACL, or authorization header is written to the ledger.

## State and idempotency

- A lock file is created atomically under a Desktop-local state directory.
- A second invocation exits `NOOP/already-running`.
- A processed bundle is keyed by the manifest and patch SHA-256 pair.
- The same bundle hash is never applied twice.
- State files are written to a temporary sibling, flushed, hashed, and renamed.
- A corrupt or partial state file returns `HOLD/state-corrupt`.

## Failure handling

The consumer is fail-closed. Important failure classes include:

```text
desktop-policy-missing
desktop-policy-disabled
backing-identity-mismatch
share-acl-unproven
patch-drop-pending
missing-bundle-meta
manifest-not-allowlisted
patch-budget-exceeded
index-lock-conflict
source-lease-conflict
dirty-overlap
changed-preimage
sha-mismatch
secret-leak-risk
filemode-blocked
wrong-sourceset
verification-failed
rollback-unproven
state-corrupt
already-running
```

No failure authorizes a fallback source root, a different patch, global Git
trust changes, or a retry loop without external-state change.

## Installation boundary

The task manager supports these operations:

- `-Action Render -TestMode`: emit a private operational task definition and a
  bounded result without changing Windows. `-CompactJson` makes the result
  machine-readable across a native process boundary.
- `-Install`: Desktop-only registration after root, identity, policy, task name,
  executable preimages, secure replacement boundaries, strict policy bytes, and
  full task identity are proven. It must be run locally as LocalSystem and stages
  only a disabled task.
- `-Status`: redacted presence/state/full-task-identity readback under the same
  attestation boundary.
- `-Uninstall`: fail closed with `HOLD/atomic-uninstall-unavailable`.

The Notebook implementation and tests use Render/TestMode and fake scheduler
fixtures. They never call remote task APIs, PowerShell remoting, WMI,
`schtasks /S`, registry startup keys, or an existing credential.

## Rollback

1. Keep the staged task disabled; this package does not activate it.
2. Do not attempt best-effort removal. Removal remains
   `HOLD/atomic-uninstall-unavailable` until an exact atomic compare-and-delete
   primitive and separate Desktop authorization are proven.
3. Preserve the redacted ledger and rejected bundle evidence.
4. If an apply completed, use the janitor-produced rollback/preimage contract and
   Desktop verification before restoring source.
5. Remove only the package files if repository rollback is requested.

## Test strategy

Tests run against temporary fixture roots and a fake janitor adapter.

### RED fixtures

- missing/disabled/malformed policy;
- identity or ACL hash mismatch;
- zero, multiple, incomplete, non-v3, or non-allowlisted bundles;
- secret, file-mode, wrong-source, dirty-overlap, lease, changed-preimage, budget,
  verification, and rollback failures;
- duplicate bundle and simultaneous invocation;
- installer invoked from a non-Desktop fixture or with a changed action hash.

### GREEN fixtures

- `OBSERVE` records a bounded decision and never calls the adapter;
- one allowlisted bundle in `APPLY` calls the fake adapter exactly once;
- a repeated run returns `NOOP/already-processed`;
- two private Render outputs and their action/full-task identity hashes are
  deterministic; native compact Render is machine-readable;
- fake Install stages an exact disabled LocalSystem task and returns
  `HOLD/desktop-enable-proof-required`; Uninstall stays fail-closed;
- ledger rows contain only the allowlisted fields.

## Success criteria

Repository tooling is complete when contract tests, existing PatchDrop janitor
tests, count-only secret scans, and temp-only install/render tests pass.

Activation is complete only when Desktop locally installs the exact reviewed task
and demonstrates one valid observe run, one invalid fail-closed run, one valid
apply fixture with rollback proof, and no application-source mutation outside the
declared bundle. Until then:

```text
toolingArtifactVerdict=APPLY
activationVerdict=HOLD
desktopFinalProof=evidence_needed
```
