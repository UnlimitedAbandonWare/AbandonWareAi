# db_agent.py — agent cheat-sheet (copy-paste)

cwd = Project Root `C:\AbandonWare\demo-1\demo-1\src`. Every command prints
one-line JSON; add `--pretty` for indented output. Flags go AFTER the subcommand.

## Read (SELECT-only, no server needed when file unlocked)

```powershell
python scripts/db_agent.py status
python scripts/db_agent.py tables
python scripts/db_agent.py tables --with-counts
python scripts/db_agent.py schema --table administrators
python scripts/db_agent.py schema                          # all base tables
python scripts/db_agent.py get-user --username admin       # role + hashPrefix only
python scripts/db_agent.py query --sql "SELECT COUNT(*) AS c FROM administrators"
python scripts/db_agent.py query --file my.sql --max-rows 50
```

## Write (explicit)

```powershell
# password via env only — never argv, never logged, never re-printed
Set-Item Env:LMS_LOCAL_ADMIN_PASSWORD '<local-dev-password>'
python scripts/db_agent.py upsert-admin --username admin --role ROLE_ADMIN
python scripts/db_agent.py upsert-admin --username admin --verify-only   # read-back only

python scripts/db_agent.py apply --file scripts/sql/foo.sql --dry-run    # plan + gate verdicts
python scripts/db_agent.py apply --file scripts/sql/foo.sql --i-mean-it  # execute
python scripts/db_agent.py apply --file f.sql --i-mean-it --allow-tables other_table
```

`apply` gate: hard deny DROP/TRUNCATE/ALTER/RENAME/GRANT/REVOKE/SHUTDOWN/
SCRIPT/BACKUP/CHECKPOINT; DML only on the write allowlist (default
`administrators`, extend per-run with `--allow-tables`).

## When the server is live (file locked → exit 3, reason=locked)

Reads still work without stopping the server via the sibling tool:

```powershell
python scripts/meta_display_db_export.py live status            # probe HTTP lane
python scripts/meta_display_db_export.py live tables
python scripts/meta_display_db_export.py live query "SELECT ..."
python scripts/meta_display_db_export.py live export            # -> sqlite bundle
python scripts/meta_display_db_export.py query "SELECT ..."     # vs latest bundle
```

Writes need the file unlocked: `.\Close-RAG.bat` (or
`scripts\stop_rag_stack.ps1 -MetaDisplay`), run the write, then `.\Start-RAG.bat`.
Never kill the JVM by hand. Copy/snapshot for inspection:
`python scripts/meta_display_db_export.py snapshot` then point
`--db-path <exportDir>/lmsdb` at the copy.

## Non-default DB

```powershell
python scripts/db_agent.py status --db-path "$env:TEMP\copy\lmsdb"   # a copied store
# or set LMS_DB_URL=jdbc:h2:file:... (file URLs only; tcp/remote → exit 2)
```
