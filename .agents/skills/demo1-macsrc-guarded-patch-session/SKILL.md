---
name: demo1-macsrc-guarded-patch-session
description: Use when a prepared MacSrc PatchIntent has focused RED evidence
---

# Demo1 MacSrc Guarded Patch Session

## Core Principle

Consume one immutable intent and one hash-bound RED record. A session plan is
non-mutating; only the existing `demo1-macsrc-smb-direct-patch` guard can open
the application-source write phase.

## Assess Readiness

Read `references/session-contract.md`, then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-macsrc-guarded-patch-session\scripts\new_guard_session_plan.ps1 `
  -Root . -IntentFile <relative-intent.json> `
  -RedEvidenceFile <relative-red-evidence.json>
```

The helper validates intent JSON, sidecar, ready marker, RED command binding,
nonzero RED exit, expected-signal boolean, current target/boundary hashes,
the exact `\\desktop-m5nov6k\MacSrc` identity, index lock, top-level PatchDrop
queue, and active source leases. It publishes a non-mutating plan with one
next action. A mapped `Y:\` is accepted only when live `PSDrive.DisplayRoot`
normalizes to that exact share.

## Act on the Plan

| Verdict and next action | Required action |
| --- | --- |
| `HOLD / REPRODUCE_RED` | Run no guard and edit nothing |
| `HOLD / RESTART_INTAKE` | Create a new intent from current hashes |
| `HOLD / RESOLVE_SOURCE_OWNERSHIP` | Stop for queue, lock, or lease owner |
| `READY_FOR_GUARD / RUN_GUARD_PREPARE` | Begin the exact guarded sequence |

**REQUIRED SUB-SKILL:** Use `demo1-macsrc-smb-direct-patch` for every mutation
phase. Run its `Prepare`, then `Verify` immediately before `apply_patch`. Patch
only declared targets, run the predeclared GREEN command, create checksum-bound
verification evidence, and use `Complete`. Use `Abort` only after rollback is
complete or when its record explicitly keeps the lease for required rollback.

## Fixed Stop Rules

- Never acknowledge away a pending PatchDrop from this skill.
- Never substitute a passing GREEN command for reproduced RED.
- Never add an undeclared target after `Verify`.
- Never treat plan `READY_FOR_GUARD` as mutation permission.
- Never chain a second defect into the same run.
- Never change global Git `safe.directory`; use the plan's filesystem-CAS mode
  when the exact MacSrc root reports dubious ownership.

The guard remains a cooperative checksum/lease protocol, not an OS hostile
writer sandbox. Desktop/runtime final proof remains evidence-needed.

## Common Mistakes

- Rewriting the intent after RED
- Recording raw test output instead of its hash and reason code
- Completing with exit code alone and no postimage hashes
- Releasing a lease while rollback is still required
- Claiming Notebook or SMB verification is Desktop final proof

Any mistake changes the session verdict to `HOLD` or `Abort`.

## Non-Trigger

Do not use this skill without an immutable intent and focused RED, for
postprocessing, for queue janitor work, or as a substitute for the
`demo1-macsrc-smb-direct-patch` mutation guard.

## Operational Contract

- owner: session readiness belongs to the Notebook evidence owner; only the
  existing guard owns the temporary application-source mutation lease.
- mutation surface: this planner writes only
  `data/agent-handoff/macsrc-guarded-patch/<runId>`; it never writes source.
- timeout: JSON snapshots are bounded local reads; the later guard lease has
  its own 1-540 minute contract.
- bounded output: intent and RED snapshots are capped at 1 MiB and the plan
  contains hashes, paths, counts, booleans, and reason codes only.
- failure policy: fail-closed for checksum drift, reparse/alternate-stream paths, intersecting or unknown lease/patch scopes, `macsrc-root-mismatch`, or source drift. Git dubious
  ownership on the exact share uses existing filesystem preimage checks.
- rollback: the planner has no source rollback; abort the authoritative guard
  and restore its declared targets (legacy sessions retain their recorded scope) if a later guarded phase changes source.
- removal: remove this planner skill/prompt only; keep the shared guard and
  source-edit lease contract intact.
- non-duplication: lease, checksum, Complete, Abort, and rollback behavior
  remain solely in `demo1-macsrc-smb-direct-patch`.
- falsifying test: produce READY from a temporary root, mutate global Git trust,
  produce READY while an intersecting or unknown-scope lock exists, or bind RED to other bytes.
