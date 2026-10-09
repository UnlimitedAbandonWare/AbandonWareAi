---
name: demo1-session-state-checkpoint
description: Preserve current instructions and independently verified delivery obligations in the existing state.md at task start/resume, requirement changes, checkpoints, and final delivery
---

# demo1 Session State Checkpoint

Long Codex sessions on this checkout hit context compaction dozens of
times and accumulate tens of millions of tokens (baseline:
`data/agent-handoff/devin-skill-friction-8eeb784c/baseline.json` —
top session ~64M tokens, 6 compactions). A compacted session loses the
working set; a fresh session re-reads it expensively. `state.md` is the
cheap bridge both ways.

## When to write

- At task start/resume and whenever the user changes scope, stop/resume or destinations,
- Right BEFORE a compaction (any time the context nears the window),
- at the end of every work bundle / checkpoint cycle,
- before handing a multi-hour goal to a new session.

## What to write — `<taskDir>/state.md`, ≤20 lines

```
goal: <one line>
done: <verified items + evidence refs>
remaining: <ordered next steps>
open leases/claims: <topics + target paths>
next command: <the literal next command line>
blockers: <firstBlockingRule / HOLD reason, if any>
continuity: <one compact awx.task-continuity.v1 JSON object; schema/commands below>
```

Rules: pointers and file:line refs, not transcripts, secrets, stored permissions or raw reasoning.
The structured line holds the required contract; prose is background only. Use the
revision-checked writer, not an unconditional overwrite. Never truncate obligations
to fit 20 lines: the writer refuses capacity overflow and leaves existing bytes intact.
Legacy files remain readable; create/upgrade the contract on new or updated tasks.

## Delivery and completion contract

Use `scripts/checkpoint_doctor.py` against this same exact state.md. The schema and
fully worked example are in `docs/agents-rules/DEMO1-TASK-CONTINUITY-DELIVERY.md`.

```powershell
python -B scripts/checkpoint_doctor.py --state <taskDir>/state.md --write-contract <local-input.json> --expected-revision <oldRevision>
python -B scripts/checkpoint_doctor.py --state <taskDir>/state.md --latest-instruction-ref <currentUserRef> --expected-revision <currentRevision>
python -B scripts/checkpoint_doctor.py --state <taskDir>/state.md --check-complete --latest-instruction-ref <currentUserRef> --expected-revision <currentRevision> --environment <verifiedHost>
```

The input JSON is a transient update packet, not a second state store. Compare latest
user instructions independently before supplying the ref/revision; copying them from
old state is not reconciliation. Preserve non-goals and paused/cancelled states.
Instruction changes require a new revision and `supersedes` pointing to the old ref.

Track attachment and user-designated local saving as separate required deliveries.
Only VERIFIED/FOUND_VERIFIED with current version/hash/environment/revision evidence
can satisfy them. The doctor reads actual source and destination bytes. FOUND_VERIFIED
means already present/matching, never proof of who saved it. Attachment proof is a
recorded delivery-tool receipt, not a filename or independent remote availability.
No delivery tool means NOT_VERIFIED; keep independent steps going and refuse DONE.
Downloads applies only when dot delivers a final user-facing directive.
Codex reviews, drafts, logs, and intermediate outputs stay in their existing project paths
(or `docs/reports/agent-reviews/<task-id>/` for a new report). For an eligible final
directive, resolve the current user's OS Downloads destination; do not hardcode it.
Follow [delivery scope](../../../docs/agents-rules/DEMO1-DELIVERY-DOWNLOADS.md).

Record access failures only as action/target/environment/time/sanitized errorCode.
Do not retry a recorded denied read through the completion checker. Remove/update
that failure only after an explicit new request or supported access-state change;
no other tool/account/path bypass. A file A read failure does not block file B writes.

Shadow preference candidates use the same contract's minimal `preferenceEvents`;
`--preference-scope <scope> --artifact-type report|directive` evaluates them read-only.
Independent verified user choices count once per task/context; quotes, retries, agent
actions and silence do not count. Conflicts suspend candidates. Current explicit
choices win without relearning. Candidates never authorize actions or satisfy delivery.
If a current authorized plan adopts one, explicitly record its actual obligation here.
External compressors/backends stay OFF: no provider, DB, daemon, API key or network call.

## Resuming in a fresh session

1. Read `<taskDir>/state.md` first, then compare the latest actual user instruction.
   Run the state check above; a mismatch is evidence needed, never silent resume.
2. `python -B scripts/checkpoint_doctor.py --run <cycle>` for in-flight
   checkpoint state.
3. `work_journal.py list --active` + lease `status` before any write —
   journal events from the old session are evidence, not permission to
   overwrite.
4. Before final DONE, use the existing completion gate with the exact task:
   `python -B scripts/demo1_goal_switch_barrier.py reject-complete --task <taskId> --text "<claim>" --latest-instruction-ref <currentUserRef> --expected-revision <currentRevision> --environment <verifiedHost>`.
   Structural success alone does not prove delivery or improve native model compaction.

## Long external document edits

- Resuming a multi-hour Google Drive .docx edit: re-open the same link in a new
  tab and verify the last recorded batch first — SSOT `$demo1-gdrive-docx-inplace-edit`.
