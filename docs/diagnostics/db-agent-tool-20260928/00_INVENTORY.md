# DB agent tool — inventory (2026-09-28, contract DEMO1-DB-AGENT-TOOL-20260928-R1)

## Target datasource (SSOT)

- `main/resources/application-meta-display.yml`:
  `spring.datasource.url: ${LMS_DB_URL:jdbc:h2:file:./var/meta-display-db/lmsdb;MODE=MariaDB;DATABASE_TO_UPPER=false}`
- File: `var/meta-display-db/lmsdb.mv.db` (created on first Start-RAG `-MetaDisplay` run).
- `local`-only profile is `jdbc:h2:mem:lmsdb` — ephemeral, never a target.
- **No AUTO_SERVER / no H2 TCP mode** in the datasource → a running Spring JVM
  holds `lmsdb.mv.db` with an exclusive lock. H2 config is not changed by this
  tool (contract: "H2 설정 멋대로 바꾸지 마").

## URL resolve order in `db_agent.py`

1. `--db-path <base|file.mv.db>` (explicit, also used by smoke on copies)
2. `LMS_DB_URL` env — only if it starts with `jdbc:h2:file:`; anything else → exit 2 `non-file-url`
3. default `var/meta-display-db/lmsdb`

Credentials: `LMS_DB_USER` (default `sa`), `LMS_DB_PASSWORD` (env only; forwarded
to `java -password` argv transiently — local dev DB only).

## Reused assets (no new dependencies, no product Java)

| Asset | Reuse |
|---|---|
| `scripts/meta_display_db_export.py` | `find_h2_jar` (Gradle cache h2-*.jar), `check_sql`/`scrub_literals` read-only gate, `quote_ident`; also the **live read lanes** when locked (`live` HTTP `/api/internal/db/meta/*`, `export` sqlite bundles) |
| `scripts/create-local-admin.ps1` + `scripts/sql/upsert-local-admin.sql.template` | the MERGE/bcrypt implementation; `upsert-admin` is a wrapper — hash prefix only, env password passthrough |
| JDK `java.exe` + `org.h2.tools.RunScript` + `CALL CSVWRITE` | single JVM launch per command; JSON assembled in Python |

## Lock probe

REAL JDBC open (`SELECT 1 FROM DUAL` via RunScript) — never an OS file-open
guess (OS open reports false-free because H2 shares write access).
Locked → exit 3 + `{"reason":"locked"}` on every DB-touching command.

## NestJS

Not present in this tree (`frontend/` = Next.js BFF `demo1-rag-next-bff`,
`main/resources/soniox-sidecar/` = Node ws sidecar). → **Python only**,
no Nest HTTP lane (contract §2).

## Related prior work

- `docs/diagnostics/local-admin-upsert-20260928/README.md` — admin upsert runbook
- `data/agent-handoff/codex-autonomy/db-agent-kit-0928-6d5a065c/` — earlier PS-kit
  intake, superseded by this contract. Its leases `db-agent-kit-0928.lock`
  (`scripts/db-agent.ps1`, `scripts/db/h2-common.ps1`) and
  `db-agent-kit-0928-b.lock` (`scripts/create-local-admin.ps1`,
  `scripts/db-agent-kit-smoke.ps1`) remain in `source-edit-locks/` as
  owner-evidence-needed; this task does not touch those paths — the optional
  one-line "prefer db_agent.py" note in the ps1 is skipped while
  `create-local-admin.ps1` is under that lease.
