---
name: demo1-macsrc-patch-postprocessor
description: Use when one MacSrc patch session reaches Complete, Abort, rollback-required
---

# Demo1 MacSrc Patch Postprocessor

## Core Principle

Adjudicate one terminal session from immutable evidence. Postprocessing never
authorizes or starts another mutation: `nextMutationAllowed=false` for every
verdict.

Use `$demo1-artifact-trace-curator` for non-mutating historical inventories.
This postprocessor remains the owner of terminal evidence adjudication.

## Required Inputs

Read `references/postprocess-contract.md`. Collect the intent, guard session,
terminal completion or abort record, GREEN verification, same-seed before/after
memory-integrity records, and a patch-specific tri-query final record. Add an
optional ready-last Docker `GREEN_VERIFICATION` result when available.

Run exactly three isolated reviews:

1. `POSITIVE_QUERY` finds retained value and the smallest verified success.
2. `NEGATIVE_QUERY` tries to disprove causality, ownership, and completeness.
3. `NEUTRAL_QUERY` reads both packets, checks A-B and B-A order, and emits the
   final record without inventing evidence.

**REQUIRED SUB-SKILL:** Use `demo1-memory-integrity-autopatch` for fixed-seed
scanning and its tri-query isolation pattern.

## Prepare the Three Reviews

Run `scripts/prepare_patch_tri_query.ps1` before dispatch. It hashes the exact
intent, session, outcome, GREEN verification, paired integrity records, and
optional Docker/Supabase evidence. It writes isolated Positive/Negative requests for
parallel execution and a dependent Neutral request. The finalizer accepts only
real packet files whose SHA-256 and common subject input map still match.
Neutral and Final must provide all official GoalContract score components and
bind each component to one or more keys from that frozen input map. The helper
recomputes the score; a submitted mismatch or foreign evidence ID yields HOLD.

## Create the Packet

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-macsrc-patch-postprocessor\scripts\new_postprocess_packet.ps1 `
  -Root . -RunId <run-id> -IntentFile <intent.json> `
  -SessionFile <session.json> -OutcomeFile <complete-or-abort.json> `
  -VerificationFile <verification.json> `
  -BeforeIntegrityFile <before.json> -AfterIntegrityFile <after.json> `
  -TriQueryDecisionFile <patch-tri-query-final.json> `
  -AutograderEvidenceFile <docker-result.json>
```

Pass `-SupabaseEvidenceFile` only when the intent marks Supabase
`REQUIRED_READ_ONLY` and project-scoped evidence exists.

The Docker input is optional for compatibility. When supplied, its sibling
job must bind the intent and GREEN verification hashes, include every target,
and preserve the no-network local-copy isolation contract. The helper
recomputes test counts and pass ratio, then applies a delta from -10 to +10.
Invalid evidence applies no delta and yields `RERUN_DOCKER_AUTOGRADER`.

## Verdicts

| Verdict | Meaning |
| --- | --- |
| `COMPLETE` | All hashes, GREEN, same-seed pairing, ordered review, and relevant external proof pass |
| `HOLD` | Evidence can be repaired without source mutation |
| `ROLLBACK_REQUIRED` | The guard still owns an incomplete rollback |

`COMPLETE` ends with `STOP`. A possible follow-up defect returns to
`demo1-macsrc-defect-intake` with a new run ID; it is never launched from the
postprocessor.

## Machine Output Rule

Copy `verdict`, `failureClasses`, and `nextAction` exactly from the helper JSON.
Do not translate, synonymize, or replace a token with prose. For the common
incomplete-evidence case the fixed tokens are:

| Evidence failure | Exact failure class |
| --- | --- |
| Missing or wrong target postimage | `verification-target-hash-mismatch` |
| Different checksum seed | `paired-seed-mismatch` |
| Missing Supabase authentication | `supabase-auth-missing` |
| Missing project scope | `supabase-project-ref-missing` |
| Invalid Docker grade | `autograder-*` reason and `RERUN_DOCKER_AUTOGRADER` |

When verification and other failures coexist, the fixed priority action is
`REPAIR_VERIFICATION_EVIDENCE`. Rerun the helper after repairing that evidence;
do not describe the repair itself as `nextAction`.

## Supabase Boundary

Use Supabase only for Database, Auth, RLS, Storage, Realtime, or another proven
Supabase seam. Require authenticated project scope, `read_only=true`, bounded
feature groups, and `mutationPerformed=false`. Store a project-ref hash, never
the ref or credentials. Missing scope is evidence-needed, not local failure.

## Common Mistakes

- Comparing different seeds, populations, or sample counts
- Accepting GREEN exit code without every target postimage hash
- Reusing a tri-query decision from another session
- Trusting the reviewer-supplied goal score without deterministic recomputation
- Trusting a Docker-submitted pass ratio instead of recomputing from counts
- Treating null metrics as zero
- Starting the next patch after `COMPLETE`

Any mistake yields `HOLD` or `ROLLBACK_REQUIRED`.

## Non-Trigger

Do not use this skill before a terminal guard outcome, to start another patch,
to repair source, or to treat caller-authored Supabase metadata as provider
runtime lineage.

## Operational Contract

- owner: the postprocess evidence owner adjudicates artifacts; Desktop owns
  final runtime proof and a new intake owns any later defect.
- mutation surface: only
  `data/agent-handoff/macsrc-patch-postprocess/<runId>`; source, OneDrive,
  Supabase, DB, credentials, and provider state remain read-only/prohibited.
- timeout: each prepared reviewer request is capped at 600 seconds.
- bounded output: each review packet is at most 256 KiB and input evidence at
  most 1 MiB; raw prompts, queries, bodies, credentials, and logs are excluded.
- failure policy: fail-closed for root, session, verification hash, no-op,
  packet hash, order, checksum pairing, secret, or publication mismatch.
- rollback: `ROLLBACK_REQUIRED` delegates to the still-owned guard lease; this
  skill never edits source or releases a rollback lease.
- removal: remove this postprocessor and prompt entry; keep terminal guard and
  integrity evidence, then regenerate no derived packets.
- non-duplication: reuses guard completion and memory-integrity schemas; the
  added helper only freezes review requests and does not implement reviewers.
- falsifying test: reach COMPLETE with a foreign root, zero source change,
  mismatched verification SHA, missing packet file, REJECT Neutral, stale
  subject input hash, mismatched goal score, or an unbound score evidence ID.
