---
name: demo1-db-agent-cli
description: Use when an agent must read, check, or change the demo-1 local DB (Meta Display lmsdb file H2) — status, query, admin upsert — including what a lock (exit 3) means.
---

# demo1-db-agent-cli

One entry for the demo-1 local store: `scripts/db_agent.py` (SSOT).
`scripts/db-agent.ps1` is a thin wrapper with the same command surface and
exit contract. Full command list: `docs/DB_AGENT_CHEATSHEET.md`. This skill
is the behaviour contract. Read-only snapshot context for other agents stays
with `$demo1-meta-display-db-export`.

## Entry (always from Project Root)

```
python -B scripts/db_agent.py status | tables | schema | get-user | verify-admin | query | apply | upsert-admin
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db-agent.ps1 -Action LockProbe|VerifyAdmin|UpsertAdmin|Query|TableExists|Count
```

Every command emits exactly one JSON document on stdout — parse it, do not
grep it. Exit codes: `0` ok | `2` usage/denied | `3` locked | `4` missing
tools/db file | `5` verify or row missing.

## Two DB lanes — do not conflate

- (A) default: file H2 `var/meta-display-db/lmsdb`. `MODE=MariaDB` in the JDBC
  URL is an H2 compatibility flag, **not** a MariaDB server. All vibe DB work
  uses lane A.
- (B) `scripts/smoke_agent_mariadb_*` targets a separate real MariaDB — only
  when the task explicitly names that profile. Never point lane-B tooling at
  lane A's file.

## Lock playbook (exit 3 / `"reason":"locked"`)

Locked means the running Start-RAG JVM holds `lmsdb.mv.db`. It is an
**answer**, not a failure:

- Never kill/restart the server and never delete `lmsdb.mv.db` to unlock.
- Reads: keep Start-RAG up; `--via auto` (default) already falls back to the
  live HTTP lane. `--via file` forces JDBC-only when offline work is intended.
- Writes: `apply --dry-run` first; a real write needs `--i-mean-it`
  (`--allow-tables a,b` to scope) or explicit user approval. When the store is
  locked, `upsert-admin --via auto` routes to the guarded live upsert
  endpoint — that is the intended path, not a workaround.
- If a preflight/pipeline step adds a DB status probe, it stays
  non-blocking: exit 3 renders as a soft warning JSON, never a gate.

## Write & secret guards

- Default to SELECT (`query`); DDL/DML only via `apply --dry-run`, then
  `--i-mean-it` with `--allow-tables`.
- Admin accounts only via `upsert-admin` (it wraps
  `create-local-admin.ps1` internally); do not build parallel JDBC scripts.
- Secrets via env only (`LMS_LOCAL_ADMIN_PASSWORD`, `LMS_DB_USER`,
  `LMS_DB_PASSWORD`, admin token envs) — never on argv. Output masks
  password/hash columns to a prefix; never print a plaintext password or a
  full hash.

## Headless calls

- PowerShell entries need `-NoProfile -ExecutionPolicy Bypass`.
- `Verify-RAG.bat` / `Status-RAG.bat` / `Debug-RAG.bat` emit one JSON doc
  with no pause when `AWX_AGENT=1` is set (headless mode).
- `python -B scripts/search_decision_query.py decision|runtime-status` is
  the search dry-run — no provider call.
- Other py/ssot pairs (leases, guards) follow the same rule: call the `.py`
  directly or use the `.ps1` thin proxy — see the entry table in
  `docs/diagnostics/db-vibe-auto-dx-20260928/README.md`.

Reading this doc or a stale goal-objective is intake, not Done —
`$demo1-codex-goal-intake-continue`.
