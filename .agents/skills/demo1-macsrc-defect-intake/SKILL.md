---
name: demo1-macsrc-defect-intake
description: Use when a concrete demo-1 defect, failing test, suspicious silent catch
---

# Demo1 MacSrc Defect Intake

## Core Principle

Create one immutable machine record from current evidence. Intake is always
non-mutating: `mutationAllowed=false`. Follow the record's single `nextAction`:
collect the missing boundary proof, reproduce RED, or stop.

## Entry Contract

Require all of these before publishing an intent:

- one concrete defect class and at least one evidence ID;
- current active-source or call-boundary evidence files;
- relative target files covered by relative watch roots;
- a focused RED command, expected RED signal, and matching GREEN command;
- no raw credential, token, header, prompt, query, or context text.

If source ownership or a target boundary is unknown, run the helper with
`-Mode Hold`, preserve the candidate in `-DefectClass`, and put the blocking
gate in `-FailureClass`. Emit `hold.json` instead of inventing target paths. A
broad catch, static smell, or old report is candidate evidence, not RED.

## Autograder Probe Hook

When an autograder emits bounded `awx.autograder.evidence.v1`, run
`scripts/prepare_autograder_probe.ps1`. It freezes the evidence hash and emits
one dispatch plan: Positive and Negative in parallel, then Neutral. Use
`scripts/advance_autograder_probe.ps1` only in the declared order:
`Finalize`, `GradeSandbox`, `PromoteIntent`, then `PlanSession`. `GradeSandbox`
delegates one immutable `RED_PROBE` job to `demo1-docker-autograder`; only a
COMPLETE failing result with the intended signal can advance. Promotion derives
bounded RED evidence from that result. Each transition requires ready-last
immutable inputs and emits a separate hash-linked record. It never runs a
patch; only `PromoteIntent` may call the existing intent builder, and
`PlanSession` stops at a non-mutating guard plan.

## Prepare the Intent

Read `references/defect-intent-contract.md`, then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-macsrc-defect-intake\scripts\prepare_defect_intent.ps1 `
  -Root . -RunId <stable-run-id> -DefectClass <reason-code> `
  -EvidenceIds <E1,E2> -TargetFiles <relative-file> `
  -WatchRoots <relative-directory> `
  -BoundaryEvidenceFiles <settings-or-call-boundary-file> `
  -BoundaryProofType GradleSourceSet|CallPath `
  -RedCommand <focused-command> -ExpectedRedSignal <redacted-signal> `
  -GreenCommand <focused-command>
```

The helper normalizes paths, hashes boundary and target preimages, and rejects
secret-like inputs. It publishes JSON and a SHA-256 sidecar, then publishes a
`.ready` marker last. Consumers ignore any record without the marker. It
refuses to overwrite an existing run.

## Transition Decision

| Observation | Decision |
| --- | --- |
| Source boundary unproven | Publish `HOLD`; do not publish an intent |
| Boundary proven and RED not run | Keep `PREPARED`; no lease or edit |
| RED passes or fails for another reason | `HOLD: red-not-reproduced` |
| RED matches and current hashes still match | Use `demo1-macsrc-guarded-patch-session` |
| No patch is needed | `NO_PATCH_NEEDED` |

**REQUIRED SUB-SKILL:** Use `demo1-memory-integrity-autopatch` when contaminated
memory, tri-query rewriting, or ablation evidence affects candidate selection.

## Common Mistakes

- Treating intent creation as patch authorization
- Recording raw failure bodies instead of a reason code or hash
- Selecting a mirror or remembered sourceSet
- Reusing a run ID after evidence changes
- Starting a second candidate in the same intent
- Skipping `Finalize`/`GradeSandbox` or manufacturing RED evidence

Any mistake changes the transition verdict to `HOLD`.

## Non-Trigger

Do not use this skill for broad smell scans, terminal patch adjudication,
Desktop final proof, direct source edits, or Supabase mutation.

## Operational Contract

- owner: Notebook evidence/intake owner; the Desktop remains final proof owner.
- mutation surface: only `data/agent-handoff/macsrc-defect-intake/<runId>`;
  active source, OneDrive, DB, credentials, and provider state are prohibited.
- timeout: each prepared subquery request is capped at 600 seconds.
- bounded output: evidence is at most 1 MiB and each request/packet at most
  256 KiB; retain hashes, counts, booleans, and reason codes only.
- failure policy: fail-closed on path, reparse, checksum, secret, schema, or
  publication errors.
- rollback: delete only the incomplete run directory after verifying no
  `.ready` marker was published; immutable ready records are superseded by a
  new run ID.
- removal: remove this repo-local skill and its prompt manifest entry; no
  application source rollback is needed.
- non-duplication: reuses the existing MacSrc guard and memory-integrity
  scanner; it creates only intake and autograder request artifacts.
- falsifying test: prove that an active-source OutputPath, junction escape, raw
  autograder field, or mutation-bearing evidence is accepted.
