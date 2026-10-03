# db-vibe-auto-dx closeout — DEMO1-DEVIN-DB-VIBE-AUTO-DX-20260928-R1

Goal: one DB/script entry for Codex/Devin/Grok vibe work — `scripts/db_agent.py`
stays SSOT; skills, rules, docs, and thin `.ps1` wrappers converge on it.
No product Java, no commit, no secret output, no server kill for locks.

## Agent kickoff card (Codex / Devin / Grok — identical)

```
Root    = C:\AbandonWare\demo-1\demo-1\src
DB      = python -B scripts/db_agent.py status|tables|schema|query|verify-admin
Lock    = exit 3 / "reason":"locked" -> reads via --via auto, never kill, never delete lmsdb.mv.db
Secrets = env only (LMS_LOCAL_ADMIN_PASSWORD / LMS_DB_*), never print
```

If a pasted PASTE conflicts with this card, the PASTE wins — this is a pointer,
not a contract override.

## Recommended entry table (py SSOT vs ps1 wrapper)

| work | recommended entry (SSOT) | thin wrapper(s) | avoid / deprecated |
|---|---|---|---|
| local DB read/write/admin | `scripts/db_agent.py` | `scripts/db-agent.ps1` | direct JDBC scripts, `create-local-admin.ps1` direct use, editing `lmsdb.mv.db`, H2 `AUTO_SERVER` |
| work scope claim/lease | `scripts/agent_scope_lease.py` | `scripts/agent_scope_lease.ps1`, `Agent-Scope.bat` | hand-editing `__patch_drop__/source-edit-locks/*` |
| dynamic port lease | `scripts/agent_port_lease.py` | `scripts/agent_port_lease.ps1`, `Agent-Port.bat` | kill-by-port / kill-by-image helpers |
| pre/post tool guard | `scripts/agent_work_guard.py` | `scripts/agent_work_guard.ps1` (hook stdin only) | bypassing guard hooks |
| lease conflict autoflow | `scripts/lease_conflict_autoflow.py` | `scripts/lease_conflict_autoflow.ps1` | forcing live foreign leases |
| DB structure gap scan | `scripts/db_gap_scanner.py` | `scripts/db_gap_scanner.ps1` (hybrid: prefers py, `-SkipPython` = standalone Select-String fallback) | — |
| MCP toolbox launch | `scripts/awx_mcp_toolbox.py` | `scripts/awx_mcp_toolbox.ps1` | re-implementing tool calls |
| work journal | `scripts/work_journal.py` | — | editing another task's journal.json |
| per-change checkpoint | `scripts/codex_work_checkpoint.py` | — | manual preimage copies |
| status doc row | `scripts/status_doc.py --expect-sha256` | — | editing `docs/PROJECT_STATUS.md` §4 rows by hand |
| search decision dry-run | `scripts/search_decision_query.py` | — | live provider calls for dry-run questions |
| conditional local git | `scripts/agent_git_vibe_commit.py` | `scripts/conditional_local_git.py` (library) | `git add -A`, `commit -a`, push |
| goal rail (separate track) | `scripts/autograde_b_rail.py`, `scripts/goal_next_auto.ps1`, `scripts/goal_next.ps1` | — | mixing into DB/tool set; treating goal-read as Done |

## Lock playbook (QF-03 fixed wording)

- exit 3 / `"reason":"locked"` = the Start-RAG JVM holds `lmsdb.mv.db`.
  It is an **answer**, not a failure.
- Keep Start-RAG running. Reads use `--via auto` (default) → live HTTP
  fallback. `--via file` = JDBC-only for intended offline work.
- Writes: `apply --dry-run` first; real writes need `--i-mean-it`
  (+ `--allow-tables`) or explicit user approval. `upsert-admin --via auto`
  uses the guarded live endpoint when the store is locked.
- Never kill the server, never delete `lmsdb.mv.db`, never delete a foreign
  lease to force progress.
- Any preflight/pipeline DB status step stays non-blocking: exit 3 renders
  as a soft warning JSON, not a gate.

## Notes / residuals (from query-agent-auto-20260928)

- DB·script friction items are landed: BAT headless JSON (`AWX_AGENT=1`),
  one-JSON-stdout contract, `--via` lanes + masking, thin ps1 wrapper,
  admin upsert/search-debug controllers + `search_decision_query.py`.
- Still `not_observed` (runtime, not docs): live probes of
  `/api/internal/db/meta/*` and `/api/internal/search/*` after the next
  reload — probe with `search_decision_query.py runtime-status` and
  `db_agent.py upsert-admin --via live` when a fresh JVM is up.
- `smoke_agent_mariadb_*` stays a separate explicit-profile lane (see
  cheatsheet "Two DB lanes").
- `db_gap_scanner.ps1` is the one intentional hybrid: it prefers the py and
  keeps `-SkipPython` standalone analysis; the SSOT line marks the py as
  primary, not the ps1 as deprecated.
