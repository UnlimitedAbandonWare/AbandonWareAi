# Desktop PatchDrop Auto-Intake Design

## Decision

Build a Desktop-owned, opt-in, fail-closed one-shot runner plus a Scheduled
Task installer. The Notebook may publish normal PatchDrop v3 producer bundles,
but it cannot install the task, change Desktop policy, or make shared files
execute themselves.

The installer must run from the Desktop canonical root. It snapshots all
existing Notebook bundle fingerprints as a baseline, copies the runner and its
contract into a Desktop-local directory, pins the hashes of the repository
janitor tools, and registers a periodic task. Consequently, pre-existing
producer artifacts are never applied retroactively.

## Alternatives Considered

1. **Desktop-local scheduled runner (selected).** After one explicit Desktop
   enrollment, each later bundle can be handled without a per-patch command.
   The local copy and pinned janitor hashes prevent a changed shared script
   from becoming an execution path.
2. **Observe-only queue watcher.** Safer but does not satisfy automatic
   integration because a Desktop command remains necessary for every patch.
3. **Hooks, profiles, prompt traces, or command interception.** Rejected as
   implicit persistence and an authority bypass.

## Architecture

### `desktop_autointake_contract.ps1`

Pure validation and planning functions:

- stable SHA-256 calculation;
- canonical topic and path validation;
- current Notebook bundle fingerprint inventory;
- policy parsing and fail-closed validation;
- candidate selection that ignores enrollment baselines and processed hashes;
- expected janitor-tool hash validation;
- Desktop-root and non-shared-root checks;
- deterministic scheduled-task action construction.

The contract does not mutate source, PatchDrop, Task Scheduler, or local
policy state.

### `desktop_autointake_once.ps1`

The Scheduled Task invokes this one-shot runner. It takes a filesystem lock so
only one invocation can run, loads the Desktop-local policy, verifies that the
root is the Desktop canonical root, verifies every pinned janitor hash, and
selects exactly one new Notebook bundle.

If zero candidates exist it exits successfully. If more than one candidate,
an active top-level patch, an invalid bundle, a dirty target overlap, an index
lock, or a tool hash mismatch exists, it records a bounded reason code and
does not mutate source.

For one eligible bundle, the runner calls the existing promotion script,
acquires a `desktop-consumer` lease, calls the existing janitor apply script,
runs the fixed Desktop verification ladder, and checks reverse applicability.
It moves a successfully verified top-level bundle to `applied`. If verification
fails after apply, it reverses the exact immutable patch. A successful rollback
moves the bundle to `rejected` with a redacted reason; rollback failure leaves
the bundle and lease evidence in place and stops all later intake.

### `desktop_autointake_setup.ps1`

`Install` is permitted only on the resolved Desktop canonical root and a local,
non-mapped filesystem. It creates the Desktop-local installation directory,
copies the contract and runner, writes the policy atomically, and registers a
single current-user Scheduled Task with `IgnoreNew` concurrency behavior.

`Status` is read-only. `Uninstall` removes the task and Desktop-local runner
files but never removes PatchDrop bundles or application source. `Plan` is a
read-only mode used from Notebook and tests.

## Data Flow

1. Producer writes patch, report, verification log, manifest, and pending
   marker; the SHA sidecar is written last.
2. Desktop task invokes the Desktop-local runner.
3. Runner verifies policy, canonical root, local filesystem, tool hashes,
   queue state, bundle sidecars, and fingerprint novelty.
4. Runner validates target paths and confirms there is no dirty overlap.
5. Existing promotion and janitor consumer gates perform the actual apply.
6. Fixed Gradle checks and reverse-apply check decide `applied` versus rollback
   and `rejected`.
7. Desktop-local state records only fingerprint, outcome, timestamp, and a
   bounded reason code. No raw query, credential, or environment dump is kept.

## Safety Invariants

- Notebook cannot register, update, or trigger the Desktop Scheduled Task.
- Existing bundles at enrollment are baseline-only and never auto-applied.
- Only `node=notebook`, schema `patchdrop-producer-v3`, and a complete single
  cumulative v3 bundle are eligible.
- The shared runner and janitor scripts are never executed after their pinned
  hashes change.
- The top-level PatchDrop queue must be empty before promotion and contain
  exactly the promoted patch before apply.
- Dirty target overlap, index lock, lease conflict, unsafe path, secret hit,
  SHA mismatch, or verification failure is fail-closed.
- No public API, database, provider, credential, environment variable name,
  application prompt boundary, or application source is changed by this setup.
- Desktop proof remains `evidence_needed` until installation and an end-to-end
  Desktop fixture run succeed.

## Verification

The test suite must prove candidate baseline exclusion, exactly-one selection,
ambiguous-candidate blocking, malformed-policy blocking, tool-hash drift
blocking, shared-root installation blocking, deterministic task-plan output,
and read-only Plan behavior.

Repository verification uses:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\desktop_autointake_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_tests.ps1 -Suite CoreGuards
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
python .\scripts\awx_mcp_completion_audit.py --root .
```

The Notebook may run `Plan` and the pure tests. Scheduled Task registration,
real apply, Gradle verification, rollback, and final bundle movement must be
re-run on Desktop.

