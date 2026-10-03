---
name: demo1-goal-switch-barrier
description: Use when a demo-1 agent's objective rotates or a new purpose must open while old in_progress journals/leases remain — closes owned journals superseded/abandoned, releases owned leases, forces skill re-resolve, and rejects instructional text mistaken for acceptance
---

# demo1 Goal-Switch Barrier

One focused gate for objective rotation. An agent's goal switching means the
old goal is over: its journal must not stay `in_progress` forever, its leases
must not linger, and the new ask needs a fresh skill resolve.

## Commands

```powershell
python -B scripts/demo1_goal_switch_barrier.py check --root . --agent <name>
python -B scripts/demo1_goal_switch_barrier.py switch --root . --agent <name> --new-purpose "<new ask>"
python -B scripts/demo1_goal_switch_barrier.py reject-complete --text "<claim>"
```

- `check`: owned `in_progress` journals (idle/stale/protection state) + owned
  claims/leases summary + `switchRequired`/`allowedOpen`. Exit 7 while a
  switch is required; `work_journal.py open` for a **new purpose** is allowed
  only when `allowedOpen=true` (or `--keep-task` names the new goal).
- `switch`: closes owned `in_progress` journals via `work_journal.py close`
  (`--result superseded` default, `--abandoned` for explicit discard),
  releases owned scope claims and ends owned source leases, then echoes
  `demo1_vibe_skill_router.py resolve "<new-purpose>"` so the new goal gets a
  fresh primary skill. `--old-task <id>` restricts closing to named tasks;
  `--keep-task <id>` exempts the new current goal; `--include-recent`
  overrides protection (operator assertion that the goal is dead).
- `reject-complete`: exits 5 (`instructional-not-acceptance`) when the text is
  a directive preamble — `... before continuing`, `Read/re-read ... before`,
  `Use $skill`, or a bare tool command line. Real acceptance sentences exit 0.
  Run it on any sentence before calling it "done" (see
  `$demo1-goal-complete-stop`).

## create_goal preflight
Check goal state before `create_goal` — unfinished → `update_goal`/complete first; a `cannot create a new goal ... unfinished goal` rejection is never retried as-is.

## Ownership and safety

- Owned = `journal.agent == <name>`, or a lease/claim `ownerId` equal to the
  agent name or to a taskId whose journal belongs to the agent. No prefix
  matching — `devin-desktop` never matches `devin`.
- Foreign journals/leases are never modified. Lease release goes through
  `source_edit_session.ps1 -Action end`, which itself refuses owner mismatch
  (exit 3) — a second physical boundary.
- A sibling session may share the agent name. Owned `in_progress` journals
  updated within `--protect-minutes` (default 30) or holding an **active**
  owned lease are skipped and reported, not closed. `--old-task` /
  `--include-recent` override only on explicit operator say-so.
- Unlinked owned leases (no claim, no task link) are ended only when expired;
  active ones are reported as `active-unlinked-owned-lease`, never force-ended.

## What this is not

Not a scheduler, not a new lock layer, not admin lockdown — PROTO-LIGHT.
It composes `work_journal.py`, `source_edit_session.ps1`, scope claims, and
the vibe skill router; it grants no write authority and deletes nothing.
