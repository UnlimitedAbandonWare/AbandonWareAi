# agent-scope-lease

One-command work-scope coordinator for parallel agents on this checkout —
Codex writing source directives while Devin applies them, Grok, Notebook
producers. Before touching files or logic, ask it who owns what; claim your
work unit; it releases on done/abort. It composes the existing lease and
journal machinery — it is not a new VCS and never grants edit authority.

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
and leave one `request-release`. **stale** (expired, owner not proven alive) —
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
