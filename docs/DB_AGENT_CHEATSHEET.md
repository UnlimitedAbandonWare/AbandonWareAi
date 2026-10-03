# DB Agent Cheatsheet (headless, query-first)

Single source of truth: `scripts/db_agent.py`. `scripts/db-agent.ps1` is a thin
wrapper that forwards to it — command names and exit codes are identical.
Entry skill: `$demo1-db-agent-cli`.

## Vibe minimal set (5 tools cover most agent work)

1. **DB**: `python -B scripts/db_agent.py status|tables|schema|query|verify-admin` — this file.
2. **Server**: `Start-RAG.bat` / `Verify-RAG.bat` — pointers only, see `docs/PROJECT_STATUS.md` §2.
3. **Debug trail**: `scripts/read_rag_debug_trail.ps1` (`$demo1-rag-debug-trail` — note the expired clean-primitive lease on it; run read-only).
4. **Git**: `python -B scripts/agent_git_vibe_commit.py` only (`$demo1-git-vibe-workflow`); never `add -A`/push.
5. **Leases**: `python -B scripts/agent_scope_lease.py` (work scope) and `agent_port_lease.py` (dynamic ports) — the `.py` is SSOT, the `.ps1` is a thin proxy. Full py↔ps1 entry table: `docs/diagnostics/db-vibe-auto-dx-20260928/README.md`.

## Two DB lanes — not one

- (A) default: file H2 `var/meta-display-db/lmsdb` via `db_agent.py`.
  `MODE=MariaDB` in the JDBC URL is an H2 compatibility flag, **not** a
  MariaDB server.
- (B) `scripts/smoke_agent_mariadb_*` = a real MariaDB lane used only when a
  task explicitly names it. Never point lane-B tooling at lane A's file.

## Goal rail — a separate track, not the DB set

`scripts/autograde_b_rail.py` and `scripts/goal_next_auto.ps1` /
`goal_next.ps1` drive objective sequencing. Reading a stale goal-objective is
intake, never Done — `$demo1-codex-goal-intake-continue`.

All commands emit exactly one JSON document on stdout (`--pretty` for indented).
Flags go **after** the subcommand.

## Lanes (`--via auto|file|live`, default `auto`)

- `file` — direct H2 file open on `./var/meta-display-db/lmsdb`. Fails with
  `locked:true` when the Spring JVM holds the file.
- `live` — HTTP against the running app (`--base-url`, default
  `http://127.0.0.1:18180`). Read lane hits `/api/internal/db/meta/*`;
  `upsert-admin` posts the guarded `/api/internal/db/meta/admin/upsert`.
- `auto` — file first; on lock, delegates reads to live automatically.
  Custom `--db-path` is guarded: a non-default path that is locked is
  refused rather than silently querying the server's different DB.

## Commands

```
python -B scripts/db_agent.py status            # url/file/lock probe (real JDBC open)
python -B scripts/db_agent.py tables [--with-counts]
python -B scripts/db_agent.py schema [--table NAME]
python -B scripts/db_agent.py get-user --username admin      # hash prefix only
python -B scripts/db_agent.py verify-admin --username admin  # exit 5 on miss; prints lane
python -B scripts/db_agent.py query --sql "SELECT ..."       # SELECT/WITH/TABLE/VALUES/EXPLAIN only
python -B scripts/db_agent.py apply --file x.sql --dry-run|--i-mean-it
python -B scripts/db_agent.py upsert-admin --username admin  # password via env only, never argv
```

## Exit codes

| code | meaning |
|------|---------|
| 0 | success |
| 2 | usage error / denied SQL |
| 3 | DB locked by live JVM (file lane) |
| 4 | DB file missing |
| 5 | `verify-admin` target absent |

## Rules

- Password/hash columns are masked to a prefix in every lane; full hashes never
  appear in output.
- No plaintext passwords on argv — env vars only.
- H2 `AUTO_SERVER` is intentionally not used; the live lane is the fallback.

## Smoke

```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db-agent-kit-smoke.ps1
# last line: RESULT fail=0 skip=0
```

## Search dry-run (no provider call)

```
python -B scripts/search_decision_query.py decision --query "..." [--base-url URL]
python -B scripts/search_decision_query.py runtime-status [--base-url URL]
```
