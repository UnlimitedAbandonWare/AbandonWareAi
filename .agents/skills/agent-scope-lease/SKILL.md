---
name: agent-scope-lease
description: >-
  Use when parallel agents on this checkout must coordinate work scope before
  touching files or logic: ask who owns what, claim a work unit, release on
  done/abort — composes the existing lease and journal machinery, never a VCS.
---

# agent-scope-lease

One-command work-scope coordinator for parallel agents on this checkout —
Codex writing source directives while Devin applies them, Grok, Notebook
producers. Before touching files or logic, ask it who owns what; claim your
work unit; it releases on done/abort. It composes the existing lease and
journal machinery — it is not a new VCS and never grants edit authority.

## Steps

1. `who` — merged inventory first: live/stale/orphan leases, claims, journals.
2. `check --path <target>` — exit 0 free, 7 conflict; journal/feature overlap is advisory unless `--strict`.
3. `claim --agent <name> --task <id> --path <t>` — opens/attaches the work journal and begins the source lease (repeat `--path` for each target).
4. `verify --task <id>` right before editing — nonzero = foreign drift, stop.
5. Edit; `heartbeat --task <id>` at progress boundaries to keep the lease live.
6. `done --task <id>` on completion, `abort` on abandon — release is mandatory on every exit path.

## Commands

```
python -B scripts/agent_scope_lease.py <action>   # or Agent-Scope.bat,
                                                  # scripts/agent_scope_lease.ps1
```

| action | effect |
|---|---|
| `who` | merged inventory: leases (with `lifecycle` = live/stale/orphan) + claims + in-progress journals |
| `check --path P [--reserve-path D] [--feature F] [--task ID] [--strict]` | exit 0 = free, 7 = lease conflict; advisories for journal-scope and feature/region overlap; `--strict` makes advisories block |
| `claim --agent NAME [--task ID] --path P... [--reserve-path D] [--feature F] [--region "path:note"] [--ttl N] [--purpose ...]` | opens or attaches the work journal, begins the source lease, writes `scope-claim-<topic>.json` in the task dir; on a conflict it auto-reclaims **stale** overlaps and retries `begin` once |
| `verify --task ID [--topic T]` | re-validate pinned targets right before editing; nonzero = foreign drift |
| `heartbeat --task ID [--topic T]` | renew lease TTL — run at progress boundaries so your lease stays `live` |
| `done --task ID [--topic T] [--note] [--close-result R --summary S]` | release lease(s) on completion (all of the task's claims unless `--topic`) |
| `abort --task ID [--topic T]` | release lease(s) on abandon (+ journal hold note) |
| `recover` | reclaim only leases whose owner is proven dead (same-host PID evidence) |
| `reclaim [--targets P...] [--task ID] [--dry-run] [--include-orphan]` | quarantine **stale** foreign leases (TTL/heartbeat expired, owner not proven alive); live leases are never touched |
| `show --task ID` | claim + lease + journal state for one task |

Typical cycle: `check --path X` → `claim --agent devin --path X --feature foo`
→ `verify --task T` → edit → `done --task T`. Release is mandatory on **every**
exit path — complete, deferred/BLOCKED (`abort`), idle/timeout, or session
cancel; an `end` left behind becomes someone else's stale cleanup.

Foreign lease classes: **live** (valid TTL / recent heartbeat / proven-alive
owner) — never forced, deleted, or stolen; proceed on non-overlapping targets
and leave one `request-release`. While blocked, wait instead of ending the
turn: `python -B scripts/codex_auto_unblock.py lease-wait --paths <blocked>
--max-min auto --enqueue --task <id>` (details:
`.agents/skills/demo1-lease-conflict-autoflow/SKILL.md` step 6). **stale** (expired, owner not proven alive) —
reclaim via `reclaim` (quarantine + `AUTO:lease-reclaimed=` journal), never via
user relay. **orphan** (unreadable lock) — manual review, `--include-orphan` to
quarantine. Never ask the user to carry "please end your lease" to another
session.

## Rules and honest limits

- Path overlap is exact or prefix either direction. A directory passed to
  `--path`/`--reserve-path` becomes a prefix reservation that blocks children,
  not siblings. A claim needs at least one file target.
- Feature (`--feature`) and region (`--region "path:note"`) labels are
  advisory — enforcement stays at path level; there is no sub-file locking.
- Expired leases still block overlapping targets until `recover` proves a
  dead owner or `reclaim` proves the lease stale; never delete lock dirs to
  work around a conflict.
- Leases are cooperative: `scripts/devin_pre_edit_guard.ps1` already enforces
  them at write time for wired tools; this tool adds the check/claim/release
  ergonomics and feature labels on top.
- State is local files only (`__patch_drop__/source-edit-locks/`,
  `data/agent-handoff/codex-autonomy/<taskId>/`). Git is never required.
- Claiming a scope does not replace the work-ledger gates: checkpoint
  preimage and three-way preflight still apply to source edits.

## Output

- `check`: exit 0 free / 7 conflict, plus advisory notes for journal-scope or
  feature/region overlap (`--strict` turns advisories into blocks).
- `claim`: writes `scope-claim-<topic>.json` under the task dir and reports
  the opened journal + begun lease; on a stale-overlap conflict it
  auto-reclaims and retries `begin` once, then reports success/failure.
- `who`/`show`: JSON inventory (`lifecycle` = live/stale/orphan) — cite it in
  reports instead of asserting ownership.
- `done`/`abort`: releases lease(s) and closes/annotates the journal; a left-
  behind `end` becomes someone else's stale cleanup — report the release in
  the task journal.

## Parallel-lane preflight (additive)

When several chats may hold the same goal, run
`python -B scripts/codex_parallel_preflight.py --root . --goal-key <key>
--scope <paths> --agent <name> --json` **before the first edit**: it returns
`OWNER`/`VERIFIER`/`TAKEOVER`/`WAIT`, lists `UNCLAIMED_EDIT` / `STALE_CLAIM`
(dry-run) and `LANE_VIOLATION`, and prints the claim + writer-begin + quota
commands in `nextCommands`. See
`.agents/skills/demo1-codex-parallel-lanes/SKILL.md`.
