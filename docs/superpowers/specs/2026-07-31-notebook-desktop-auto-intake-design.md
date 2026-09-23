# Notebook-to-Desktop Tooling Auto-Intake Design

Date: 2026-07-31

Status: system-selected design approved by user delegation; implementation pending

## Goal

Allow a Notebook-produced PatchDrop v3 bundle to be discovered and applied during the Desktop's normal `scripts/goal_next.ps1` command without a separate Desktop intake command. Automatic mutation is limited to tooling, tests, skills, and their bounded evidence. Application source is discovered but remains `HOLD`.

The integration must remain visible, reversible, hash-verified, lease-protected, and fail closed. It must not add a daemon, scheduled task, shell-profile hook, hidden persistence, or a second PatchDrop protocol.

## Evidence Snapshot

- Notebook canonical workspace: `Y:\`
- Backing identity: verified against the repository-owned SHA-256 baseline
- Index lock: absent
- Active source-edit leases: 0
- Active top-level PatchDrop patches: 0
- Git metadata from the Notebook checkout: unavailable; Desktop final proof remains required
- Existing normal Desktop entrypoint: `scripts/goal_next.ps1`
- Existing fail-closed preflight: `scripts/goal_next_auto.ps1`
- Existing producer, promotion, lease, and apply primitives:
  - `__patch_drop__/producer_bundle.ps1`
  - `__patch_drop__/producer_bundle.py`
  - `__patch_drop__/janitor_promote_producer_pending.ps1`
  - `__patch_drop__/source_edit_session.ps1`
  - `__patch_drop__/janitor_apply_one.ps1`

## Three-Way Evaluation

### POSITIVE_QUERY

- candidateGoal: prepend one Desktop-only tooling intake stage to the normal `goal_next` path
- validatedAssumptions: the wrapper, nested producer bundle contract, promotion guard, source lease, SHA/secret scan, and apply guard already exist
- reusableAssets: the six files listed in the evidence snapshot and their regression tests
- expectedUserValue: no separate Desktop intake command for eligible Notebook tooling bundles
- minimalVerification: fake Desktop-root RED/GREEN tests plus the existing PatchDrop janitor suites
- unknowns: Desktop runtime proof until the hook executes from the canonical Desktop root

### NEGATIVE_QUERY

- challengedGoal: unrestricted automatic application of every Notebook patch
- falsifiers: application-source path, multiple ready markers, top-level queue ambiguity, stale or changed bundle bytes, missing lease, missing tests, failed verification, or attempted modification of the intake trust boundary
- authorityRisks: Notebook evidence cannot replace Desktop final proof
- safetyRisks: arbitrary application-source mutation, command injection, partial apply, self-expanding policy, and background persistence
- smallestDisconfirmingProbe: a fake bundle that mixes one allowed tooling path with one `main/` path must remain unapplied

### NEUTRAL_QUERY

- verdict: APPLY
- selectedOrRewrittenGoal: automatically apply exactly one verified Notebook tooling bundle during non-status Desktop `goal_next`; hold every broader bundle
- goalScore: 82
- decisiveEvidence: existing wrapper and PatchDrop consumer guards provide the required boundaries without a new service
- rejectedClaims: zero-command automation requires hidden persistence; producer-side verification is Desktop final proof; all source patches are safe to auto-apply
- orderStable: true
- nextSingleProof: focused fake-root auto-intake regression suite
- confidence: H

Score inputs are evidenceStrength 0.92, causalStrength 0.88, verificationFeasibility 0.90, userValue 0.92, reversibility 0.90, costEfficiency 0.84, timeFit 0.84, blastRadius 0.16, ambiguity 0.12, and authorityOrSafetyExpansion 0.12.

## Considered Approaches

### A. Normal-command pull hook (selected)

`goal_next.ps1` invokes a Desktop-only intake helper before `goal_next_auto.ps1` freshness reuse. The helper consumes existing PatchDrop artifacts through the existing janitor and lease scripts. This is observable, bounded, testable, and requires no separate routine command.

### B. Background watcher or scheduled task (rejected)

A watcher could apply a bundle without any Desktop command, but it creates persistence, races with human edits, complicates rollback, and expands authority beyond the request.

### C. Automatic promotion only (not selected)

Promotion-only automation is safer but still leaves a separate apply action, so it does not meet the requested user experience for eligible tooling bundles.

## Scope

### Eligible automatic mutation

All patch targets must be within these tooling families:

- `.agents/skills/`
- `.codex/skills/`
- `.superpowers/`
- `agent-prompts/`
- `docs/superpowers/`
- `scripts/` test files matching `test_*.py` or `*_tests.ps1`
- `tools/`
- `verification/`
- task-scoped `data/agent-handoff/` evidence

Generated caches such as `__pycache__`, `.pyc`, build output, Gradle caches, and Node caches are always rejected.

### Protected trust boundary

An automatically consumed patch must not modify:

- `scripts/goal_next.ps1`
- `scripts/goal_next_auto.ps1`
- the new auto-intake implementation or its policy constants
- anything under `__patch_drop__/`
- `AGENTS.md`
- Gradle settings, build scripts, wrappers, CI, credentials, environment files, DB/DDL, or deployment configuration
- `main/`, `app/`, or any other application source tree

A protected or unknown path yields `tooling-scope-hold`; it is not promoted or applied.

## Architecture

### 1. Desktop-only trigger

Add `scripts/desktop_patchdrop_auto_intake_lib.ps1` for pure discovery/path-classification helpers and `scripts/desktop_patchdrop_auto_intake.ps1` for orchestration. `scripts/goal_next.ps1` invokes the orchestrator only for non-`-Status` calls and before the delegated `-EnsureFresh` path.

The helper mutates only when the resolved root is the local Desktop canonical root and is not a mapped or UNC root. Notebook execution from `Y:\` reports `not-desktop-canonical-root` and exits successfully without mutation. `-Status` remains read-only.

### 2. Deterministic discovery

The helper accepts only one marker matching the canonical `topic-slug.notebook-pending.md` grammar, one matching nested v3 bundle, and zero active top-level patches. It derives the topic from that grammar rather than timestamps. Multiple markers, multiple bundles, or a pre-existing top-level patch produce `patch-drop-pending`.

The existing promotion script remains the only nested-to-top-level promotion mechanism. No files are copied by a new protocol.

### 3. Tooling path classifier

Before promotion, the helper reads the SHA-covered patch bytes and parses canonical Git diff headers. Every target must pass the tooling allowlist, generated-file denylist, canonical relative-path validation, and protected-boundary denylist. Binary patches, deletes, renames, noncanonical file modes, and empty patches are rejected.

At least one changed focused test is required. Python tests are restricted to changed `scripts/test_*.py` paths; PowerShell tests are restricted to changed `scripts/*_tests.ps1` paths. The helper never executes a command string stored in a report or manifest.

### 4. Lease, promotion, and apply

After classification, the helper:

1. promotes the exact nested bundle through `janitor_promote_producer_pending.ps1`;
2. acquires a `desktop-consumer` lease with a fresh random owner ID;
3. invokes `janitor_apply_one.ps1` with the manifest-pinned bare patch name and owner ID;
4. preserves the lease through Desktop verification;
5. always releases the lease in `finally`.

Existing promotion and apply scripts retain ownership of isolation, SHA, secret, queue, path, preimage, reparse, and lease checks.

### 5. Desktop verification and rollback

After apply, the helper runs only Desktop-owned derived commands:

- `git diff --check`
- `python -m pytest -q` followed by the changed Python test paths, when present
- each changed `*_tests.ps1` through a direct `powershell -File` argument array, when present
- count-only secret scanning of the applied diff
- `git apply --reverse --check` against the exact verified snapshot

Each process has a bounded timeout and bounded/redacted output. No shell command supplied by the bundle is evaluated.

If verification fails, the exact patch is reverse-applied while the lease remains held. The bundle moves to `rejected/` with a reason-code-only sidecar. If rollback verification fails, the helper returns `rollback-failed`, leaves the bundle and evidence in place, and stops `goal_next`.

On success, the bundle and marker move to `applied/`, an intake result JSON records hashes, counts, timings, and reason codes, and normal `goal_next_auto.ps1` execution continues against the newly integrated tooling.

## Failure Model

Fail-closed classifications include:

- `not-desktop-canonical-root` (safe no-op)
- `patch-drop-pending`
- `tooling-scope-hold`
- `generated-artifact-blocked`
- `focused-test-missing`
- `promotion-failed`
- `source-lease-conflict`
- `apply-failed`
- `focused-verification-failed`
- `secret-leak-risk`
- `rollback-failed`

Only `no-ready-bundle` and `not-desktop-canonical-root` are nonblocking no-ops. Every other classification stops normal continuation.

## Testing

The implementation follows RED, GREEN, then broader proof.

### Focused RED cases

1. A valid Notebook tooling bundle is currently detected only as pending and is not integrated.
2. A fresh latest pointer can currently bypass any future intake unless the wrapper calls intake first.

### Required GREEN cases

1. Exactly one eligible Notebook tooling bundle is promoted, leased, applied, verified, archived, and followed by normal goal-next.
2. `-Status` never invokes intake mutation.
3. Notebook or mapped-root execution is a non-mutating no-op.
4. Application source, protected trust-boundary files, generated artifacts, missing tests, multiple markers, multiple top-level patches, bad SHA, secret hits, and lease conflicts all fail before mutation.
5. A focused test failure reverses the exact patch before releasing the lease.
6. A rollback failure stops continuation and preserves evidence.
7. A fresh latest pointer does not suppress intake discovery.

### Verification commands

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_patchdrop_auto_intake_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_promote_producer_pending_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\goal_next_auto_tests.ps1
```

The real Notebook bundle remains `desktopFinalProof=evidence_needed` until these gates run from the Desktop canonical root.

## GoalContract

- goalId: `notebook-desktop-tooling-auto-intake-v1`
- rewrittenUserIntent: integrate eligible Notebook tooling automatically during normal Desktop goal processing
- desiredOutcome: one safe, reversible, no-extra-command Desktop intake path
- measurableSuccess: all GREEN cases pass and the real tooling bundle reaches `applied/` only after Desktop verification
- nonGoals: background service, scheduled task, application-source auto-apply, DB/credential/deployment mutation
- authorizedMutationSurface: the eligible tooling families and the three implementation/test files named in this design
- prohibitedSurface: application source and the protected trust boundary
- evidenceBaseline: the evidence snapshot in this document
- assumptions: Desktop normal flow invokes `scripts/goal_next.ps1` from its canonical local root
- constraints: single bundle, v3 manifest, SHA, zero secret hits, lease, bounded derived tests, rollback
- verificationOwner: Desktop
- verificationCommands: the four commands above
- rollback: reverse-apply the exact patch while the lease is held; restore the wrapper hook from its preimage for setup rollback
- stopConditions: any failure model classification other than the two safe no-ops
- timeBudgetMinutes: 180
- goalScore: 82
- verdict: APPLY
- evidence_needed: Desktop canonical execution output

## SourceDirective

- directiveId: `desktop-tooling-auto-intake-v1`
- sourceOwner: desktop
- provenRoot: `Y:\` provides Notebook supporting evidence; Desktop canonical root must prove itself at runtime
- provenBranch: evidence_needed
- activeSourceSets: root `main/java`, `main/resources`; app `app/src/main/java_clean`, `app/src/main/resources` (excluded from this tooling-only patch)
- targetFiles: `scripts/desktop_patchdrop_auto_intake_lib.ps1`, `scripts/desktop_patchdrop_auto_intake.ps1`, `scripts/desktop_patchdrop_auto_intake_tests.ps1`, `scripts/goal_next.ps1`
- callPathOrBoundary: `goal_next.ps1` non-status entry -> Desktop-only intake -> existing promotion/lease/apply guards -> focused verification -> `goal_next_auto.ps1`
- beforeBehavior: pending Notebook bundles cause `patch-drop-pending` and require a separate Desktop intake command
- afterBehavior: one eligible tooling bundle is safely integrated before normal goal processing; broader bundles HOLD
- excludedFilesAndMirrors: all application source, inactive mirrors, generated outputs, PatchDrop guard implementations, policy boundary files
- publicApiChange: forbidden
- secretMutation: forbidden
- redTest: valid fake Notebook tooling bundle remains unapplied under the current wrapper
- greenTest: the required GREEN cases above
- exactVerificationCommands: the four commands above
- expectedEvidence: exit codes, bundle hashes, path/test counts, secret hit count, lease lifecycle, rollback result, final archive state
- failureClassifications: the failure model above
- rollback: remove the wrapper call and the three new tooling files without touching unrelated work; reverse any failed consumed patch under lease
- patchdropContract: exactly one complete manifest-pinned cumulative v3 bundle; ready marker published last
- desktopFinalProof=evidence_needed

## Self-Review

- Placeholder scan: no unresolved markers remain.
- Internal consistency: only the normal non-status Desktop path can mutate; status and Notebook paths remain read-only.
- Scope: one bounded tooling intake feature; application source automation is explicitly excluded.
- Ambiguity: automatic means pull-on-normal-command, not background execution.
