# MacSrc Defect Session Skill Family Design

## Goal

Create a repo-local skill family that lets a future agent convert one concrete
defect into one bounded MacSrc session and move through `Prepare -> Verify ->
RED/GREEN -> Complete|Abort` without inventing artifact shapes or bypassing the
existing SMB guard.

## Architecture

The family has three independently triggered skills:

1. `demo1-macsrc-defect-intake` creates an immutable, non-mutating
   `PatchIntent` after active-source and RED evidence are classified.
2. `demo1-macsrc-guarded-patch-session` consumes one validated intent and uses
   `demo1-macsrc-smb-direct-patch` as the sole application-source mutation
   guard. It does not duplicate the lease, checksum, PatchDrop, or rollback
   implementation.
3. `demo1-macsrc-patch-postprocessor` consumes a Complete, Abort, or Hold
   session, reruns fixed-seed integrity evidence, records ordered
   Positive/Negative/Neutral adjudication, and emits either a next candidate or
   a stop verdict. It never chains a second mutation automatically.

Korean prompt templates under `agent-prompts/agents/` make the three entry
points discoverable. They are repository prompt packs, not personal artifact
templates.

## Data Flow

```text
Defect evidence
  -> PatchIntent (mutationAllowed=false, content hash)
  -> Guard session (intent hash + session hash + lease)
  -> RED reproduction
  -> one declared MacSrc patch
  -> GREEN evidence (command + exit + postimage hashes)
  -> Complete or Abort
  -> fixed-seed integrity delta + tri-query adjudication
  -> STOP | NEXT_CANDIDATE
```

## Authority and Mutation Boundaries

- Canonical source identity is `\\desktop-m5nov6k\MacSrc`; `Y:\` is accepted
  only when its live mapping proves the same share.
- Reads, searches, web access, tools, and explicitly selected non-source output
  remain unrestricted.
- Persistent application-source writes are allowed only through the existing
  direct guard and only for declared active-source targets.
- OneDrive is not a source, prompt-pack, session, or evidence destination.
- Skills, prompt packs, tests, and compact handoff evidence are tooling changes;
  no Java application source is part of this implementation.
- Commit, push, deploy, public API, credentials, environment names, database,
  and Supabase mutation remain forbidden.

## Supabase Lane

Supabase is demand-driven. It is `NOT_APPLICABLE` unless the defect touches
Database, Auth, RLS, Storage, Realtime, or another Supabase boundary. A relevant
session requires an authenticated `project_ref` scoped read-only evidence lane.
Missing authentication or scope produces `supabase-auth-missing` or
`supabase-project-ref-missing`; it never blocks unrelated local evidence and
never authorizes SQL, migration, policy, or data mutation.

## Failure Classes

- `intent-not-ready`
- `source-boundary-unproven`
- `red-not-reproduced`
- `patch-drop-pending`
- `guard-preimage-changed`
- `verification-evidence-invalid`
- `green-failed`
- `rollback-required`
- `paired-seed-mismatch`
- `postprocess-order-unstable`
- `supabase-auth-missing`
- `supabase-project-ref-missing`

## Evidence Contract

Every artifact is UTF-8 JSON or Markdown under MacSrc. Machine records include
`schemaVersion`, `runId`, UTC time, input hashes, reason codes, counts, command
strings, exit codes, and relative paths. They exclude raw prompts, queries,
context, credentials, headers, cookies, and environment dumps.

The postprocessor reports only `COMPLETE`, `HOLD`, or `ROLLBACK_REQUIRED`.
`COMPLETE` stops the current run. A no-patch conclusion or follow-up candidate
is descriptive review output only and must begin a new intake/run; it is not a
terminal machine verdict and never chains mutation.

## 2026-07-29 Adversarial Review Addendum

The reviewed implementation additionally requires:

- handoff-only output roots with reparse rejection for all non-mutating tools;
- one locked byte snapshot for RED JSON parsing and SHA-256 binding;
- directory-shaped source lease detection and `smb-repo-owner-mismatch` HOLD;
- completion-to-GREEN evidence SHA binding, canonical root binding, and
  rejection of no-op completions;
- real Positive/Negative/Neutral packet files bound to one subject input map;
- an autograder probe hook that prepares three non-mutating reviews but never
  opens a source patch;
- caller-authored Supabase evidence labeled untrusted supporting metadata with
  `runtimeLineageVerdict=HOLD`.

## Verification

- Pressure-test every skill without and then with the skill.
- Test deterministic scripts through behavior, not source-text greps.
- Initialize each skill with `skill-creator/scripts/init_skill.py`.
- Validate each skill with `quick_validate.py` before starting the next skill.
- Run the repo-local family postprocessor and prompt-manifest checks last.
- Desktop runtime proof remains `evidence_needed`; this tooling-only change does
  not claim application runtime success.
