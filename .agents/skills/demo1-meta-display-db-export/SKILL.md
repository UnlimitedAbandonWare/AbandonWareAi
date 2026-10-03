---
name: demo1-meta-display-db-export
description: >-
  Use when Grok/Devin/Codex/Cline need shared read-only Meta Display conversation
  DB context under var/meta-display-db without opening the live H2 file while the
  JVM holds it
---

# demo1-meta-display-db-export

## Why
Meta Display durable store is H2 file DB:
`var/meta-display-db/lmsdb.mv.db` (profile `meta-display`).
Agents must **not** attach JDBC to that file while Start-RAG holds the lock.
Two safe read lanes exist; pick per server state:

- **Server down** → `snapshot` / `export` copies the file and converts to
  `export.sqlite` + `csv/` + `schema.sql` under
  `var/meta-display-db/export/<runId>/`.
- **Server up** → `live` lane hits read-only admin endpoint
  `/api/internal/db/meta` (tables / columns / query) — the same endpoint any
  agent can call directly with the admin token header.

Shared agent context for Display state is a **read-only** surface, not live
mutators.

## Do
1. Project root: `<repo>`
2. Status / offline snapshot:
```powershell
python -B scripts/meta_display_db_export.py status
python -B scripts/meta_display_db_export.py snapshot            # fails while JVM holds lock
python -B scripts/meta_display_db_export.py export              # snapshot -> export.sqlite/csv/schema.sql
python -B scripts/meta_display_db_export.py export --from <h2-file-prefix>  # any unlocked H2 file
```
3. Query the latest export (SQLite, SELECT-only gate):
```powershell
python -B scripts/meta_display_db_export.py tables
python -B scripts/meta_display_db_export.py schema [table]
python -B scripts/meta_display_db_export.py query "SELECT * FROM chat_message LIMIT 20"
python -B scripts/meta_display_db_export.py tail chat_message -n 10
```
4. Query the running server (lane: HTTP, no JDBC file open):
```powershell
python -B scripts/meta_display_db_export.py live status
python -B scripts/meta_display_db_export.py live tables
python -B scripts/meta_display_db_export.py live query "SELECT count(*) FROM chat_session"
python -B scripts/meta_display_db_export.py live export   # pull tables over HTTP -> export bundle
```
   Endpoint: `http://127.0.0.1:18180/api/internal/db/meta` — `GET /tables`,
   `GET /columns?table=NAME`, `GET|POST /query` (`{"sql": ..., "maxRows": n}`).
   Auth: existing admin guard on `/api/internal/**` — header `X-Admin-Token`
   (or `X-Owner-Token`); the script auto-reads `var/dev-admin-token.txt` or
   env `META_DB_ADMIN_TOKEN`/`DOMAIN_ALLOWLIST_ADMIN_TOKEN`, or pass `--token`.
   `403` = auth-blocked (local endpoint token missing — fix token), not a DB
   outage. That is the **local server** verdict; provider-API 401/403 classify
   per `docs/API_ROUTING_SPEC.md` §External API failure classification.
   **Proto-open note** (AGENTS.md `DEMO1-PROTOTYPE-AUTH-LIGHT`, vibe default
   `demo.auth.proto-open=true` in `local,meta-display`): `/api/internal/**` answers
   `200` with **no token at all**, so a missing `X-Admin-Token` is not a blocker and
   a `403` is not a vibe defect — fall back to the offline `snapshot` / `export`
   lane. Never add a second admin/token gate, an extra role check, or a fail-closed
   matcher here to "make the endpoint work"; harden only when the user asks.
   proto-open (`demo.auth.proto-open=true`, vibe default): the guard grants
   access without a token, so `200` with no header is the intended result —
   a missing token is not a blocker to "fix", and do not harden the guard to
   force `403`; on the harden path (`proto-open=false`) `403` remains the
   auth-blocked signal above.
5. Cross-agent handoff: point teammates at `export/<runId>/` (manifest hashes)
   or the live endpoint — never at the live `lmsdb.mv.db`.

## SQL policy (both lanes)
- SELECT / WITH / TABLE / VALUES / EXPLAIN only; single statement.
- Writes, DDL, `SET`, `CALL`, file functions (`CSVWRITE`, `FILE_READ`, …) rejected.
- Rows capped (default 200, hard 5000); cells truncated at 64 k chars;
  query timeout 10 s on the server lane.

## Don't
- Do not open `jdbc:h2:file:./var/meta-display-db/lmsdb` from an agent while the
  wear/dev server is running.
- Do not delete or rewrite `lmsdb.mv.db` / `lmsdb.trace.db` from this skill.
- Do not put secrets from `.secrets/` into the export note; never print token
  values.
- Do not treat `application-local.yml` in-memory H2 as the durable store.
- Kill switch: `meta.db.query.enabled=false` removes the live endpoint.

## Related
- SSOT URL: `main/resources/application-meta-display.yml` → `LMS_DB_URL` default
  `jdbc:h2:file:./var/meta-display-db/lmsdb;...`
- Live endpoint source:
  `main/java/com/example/lms/api/MetaDisplayDbQueryController.java`
  (gate contract: `src/test/java/com/example/lms/api/MetaDisplayDbQueryGateTest.java`)
- Agent session health (different DBs): `$agent-session-watchdog`
- Device bus / handoff journals remain the general shared ledger; this skill is
  Display DB evidence only.
