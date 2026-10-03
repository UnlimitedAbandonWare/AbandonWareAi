---
name: demo1-session-state-checkpoint
description: Use before context compaction, at the end of each work bundle, or when a demo-1 session's cumulative tokens run large — persist a ≤20-line state.md so a fresh session resumes without re-reading history
---

# demo1 Session State Checkpoint

Long Codex sessions on this checkout hit context compaction dozens of
times and accumulate tens of millions of tokens (baseline:
`data/agent-handoff/devin-skill-friction-8eeb784c/baseline.json` —
top session ~64M tokens, 6 compactions). A compacted session loses the
working set; a fresh session re-reads it expensively. `state.md` is the
cheap bridge both ways.

## When to write

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
```

Rules: pointers and file:line refs, not transcripts. No secret values.
Regenerate (overwrite) each checkpoint — stale state is worse than none.

## Resuming in a fresh session

1. Read `<taskDir>/state.md` first (≤20 lines).
2. `python -B scripts/checkpoint_doctor.py --run <cycle>` for in-flight
   checkpoint state.
3. `work_journal.py list --active` + lease `status` before any write —
   journal events from the old session are evidence, not permission to
   overwrite.
