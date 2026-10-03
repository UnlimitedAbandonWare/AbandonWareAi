<!-- moved-from: AGENTS.md L66-L71 sha256=2dadacd648d4feaf7a575f0366d8c7db565798adbfe6879ed313d51525c73662 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-DB-AGENT-SSOT -->
## Local DB agent entry (lmsdb file H2)
- SSOT `scripts/db_agent.py` (run from root): `status|tables|schema|get-user|verify-admin|query|apply|upsert-admin`; `scripts/db-agent.ps1` is a thin wrapper with the same exits 0/2/3/4/5. Skill `$demo1-db-agent-cli`; cheatsheet `docs/DB_AGENT_CHEATSHEET.md`; py/ps1 entry table `docs/diagnostics/db-vibe-auto-dx-20260928/README.md`.
- exit 3 `locked` = the Start-RAG JVM holds `lmsdb.mv.db` — an answer, not a failure: never kill the server or delete the file; reads use `--via auto` live fallback, writes need `--dry-run` then `--i-mean-it`+`--allow-tables` or user approval.
- `MODE=MariaDB` is H2 compat, not a MariaDB server (`smoke_agent_mariadb_*` is a separate explicit lane). Secrets env-only (`LMS_LOCAL_ADMIN_PASSWORD`/`LMS_DB_*`); output masks hashes.
<!-- END DEMO1-DB-AGENT-SSOT -->
