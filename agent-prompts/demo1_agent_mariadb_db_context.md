# demo-1 Agent MariaDB DB-Context Prompt

Use this prompt when an agent needs read-only MariaDB-backed runtime context from the
Desktop canonical root.

## Safety Contract

- Do not put DB usernames, passwords, admin tokens, or connection URLs into source.
- Read credentials only from env vars or the operator's local secret mechanism.
- Do not print `LMS_DB_PASSWORD`, `AWX_ADMIN_TOKEN`, `DOMAIN_ALLOWLIST_ADMIN_TOKEN`,
  Authorization headers, cookies, or full environment dumps.
- Agents must not run arbitrary SQL against the operator database. Use the redacted
  `/agent/db-context/**` endpoints or the `agent_db_snapshot` MCP-style tool.
- Treat Mac mini or Notebook DB observations as supporting evidence only. Desktop is
  the final verifier.

## Desktop Env Names

Set these outside source before booting the app:

```powershell
$env:LMS_DB_URL = "jdbc:mariadb://127.0.0.1:3306/<database>?useUnicode=true&characterEncoding=utf8mb4"
$env:LMS_DB_USERNAME = "<db-user>"
$env:LMS_DB_PASSWORD = "<db-password>"
$env:LMS_DB_DRIVER = "org.mariadb.jdbc.Driver"
$env:LMS_DB_DIALECT = "org.hibernate.dialect.MariaDBDialect"
$env:LMS_DB_HIKARI_CONNECTION_TIMEOUT = "3000"
$env:AGENT_DB_CONTEXT_QUERY_TIMEOUT_SECONDS = "2"
$env:DOMAIN_ALLOWLIST_ADMIN_TOKEN = "<admin-token>"
$env:AWX_ADMIN_TOKEN = $env:DOMAIN_ALLOWLIST_ADMIN_TOKEN
```

## Boot For Local Test

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
```

Run the direct JDBC credential smoke before booting Spring:

```powershell
$env:AGENT_DB_CONTEXT_JDBC_SMOKE_REQUIRED = "true"
.\gradlew.bat agentDbContextJdbcSmoke --no-daemon --project-cache-dir $pcd
```

Expected direct-smoke success shape:

```text
[AWX][agent-db-context][jdbc-auth] ok=true databaseProduct=MariaDB majorVersion=<n> readOnly=false
```

If env is missing, the smoke reports fixed names only:

```text
[AWX][agent-db-context][jdbc-auth] ok=false reason=missing-env missing=LMS_DB_URL,LMS_DB_USERNAME,LMS_DB_PASSWORD
```

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local --server.port=8080" --no-daemon --project-cache-dir $pcd
```

## Agent Snapshot Probe

```powershell
$env:AWX_AGENT_DB_CONTEXT_BASE_URL = "http://127.0.0.1:8080"
.\scripts\smoke_agent_mariadb_context.ps1 -Endpoint snapshot -RequireDbEnv -RequireRuntime
```

For a no-secret preflight before booting the app, run:

```powershell
.\scripts\smoke_agent_mariadb_context.ps1 -SkipHttpProbe
```

The smoke script prints only env presence, endpoint host/port, TCP state,
HTTP status, and fixed fail-soft reason fields. It must not print raw DB
credentials, admin tokens, full JDBC URLs, or raw DB rows.

Expected success shape:

```json
{
  "ok": true,
  "endpoint": "snapshot",
  "tokenPresented": true,
  "decision": "agent_db_snapshot_loaded",
  "snapshot": {
    "memory": {},
    "ledger": {},
    "strategy": {}
  }
}
```

Expected fail-soft shape when DB is down, credentials are wrong, or a DB query times out:

```json
{
  "ok": false,
  "decision": "agent_db_snapshot_http_error",
  "failReason": "http-503",
  "snapshot": {
    "enabled": false,
    "disabledReason": "db_context_unavailable",
    "failureClass": "timeout"
  }
}
```

## Prompt/Trace Contract

- Prompt construction stays on `PromptBuilder.build(PromptContext)`.
- `AgentDbContextPromptInjector` may add only short aggregate summaries to
  `learningContextSummary`.
- Raw memory content, raw queries, DB password values, and admin token values must not
  appear in prompt text, TraceStore, debug events, logs, or MCP output.
- Expected TraceStore breadcrumbs:
  - `agent.dbContext.prompt.injected`
  - `agent.dbContext.prompt.failSoft`
  - `agent.dbContext.<endpoint>.failSoft`
  - `agent.dbContext.<endpoint>.reason`
  - `agent.dbContext.<endpoint>.failureClass`
