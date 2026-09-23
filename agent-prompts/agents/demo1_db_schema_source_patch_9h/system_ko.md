# demo-1 DB Schema Source Patch 9h Directive

Generated: 2026-06-14 Asia/Seoul
Workspace: `C:\AbandonWare\demo-1\demo-1\src`

이 프롬프트는 Codex / Antigravity / Claude 계열 코딩 에이전트가 demo-1 Desktop canonical root에서 DB 구조가 잘못된 문제를 9시간 한도 안에서 안전하게 정량화하고, 필요한 Java/Spring/SQL/tooling 소스만 최소 diff로 패치하도록 주는 소스 수정 지시서다.

`{스터프2}`는 설계 claim map이다. `{스터프1}`로 만든 스킬과 기존 prompt pack은 보조 지침이다. 최종 판단은 live repo 파일, 실제 DB 스키마 관측, Gradle 출력, secret-safe smoke 결과가 우선한다.

Supabase 관련 계약은 2026-06-14 기준 공식 문서로 확인했다.

- Supabase changelog: 2026-04-28 "Tables not exposed to Data and GraphQL API automatically"; 새 public table은 Data API 노출에 explicit grant가 필요해진다.
- Supabase "Securing your API": Data API 접근은 Postgres grants와 RLS가 함께 결정한다.
- Supabase CLI reference: CLI 명령은 `supabase --help`, `supabase <group> --help`로 현재 버전을 확인하고 사용한다.
- Supabase MCP docs: remote MCP URL은 `https://mcp.supabase.com/mcp`; 가능하면 project-scoped, read-only로 시작한다.

---

## 0. Current Repo Evidence

이 지시서 작성 시점의 live evidence:

```text
root: C:\AbandonWare\demo-1\demo-1\src
git metadata: unavailable from this mirror; do not invent branch/worktree proof
index.lock: absent by local path check
PatchDrop top-level pending .patch: none observed
active backend source: main/java, main/resources
active :app source: app/src/main/java_clean, app/src/main/resources
build owner: build.gradle.kts
Spring Boot: 3.3.4
Java: 17
LangChain4j: 1.0.1 only
database drivers: MariaDB, MySQL, H2 runtimeOnly
default profile schema ownership: application.properties uses LMS_JPA_DDL_AUTO validate
local profile schema ownership: application-local.yml uses LMS_JPA_DDL_AUTO update
Flyway: no default dependency; main/resources/db/ddl contains manual MariaDB/MySQL DDL scripts
current DB context tool surface: /agent/db-context/**, agent_db_snapshot, agentDbContextTest, agentDbContextJdbcSmoke, scripts/smoke_agent_mariadb_context.ps1
```

Already-active source seams:

```text
main/java/com/example/lms/agent/context/AgentDbContextProvider.java
main/java/com/example/lms/agent/context/AgentDbContextController.java
main/java/com/example/lms/agent/context/AgentDbContextPromptInjector.java
main/java/com/example/lms/agent/context/AgentDbContextProperties.java
src/agentDbContextTest/java/com/example/lms/agent/context/AgentDbContextJdbcSmoke.java
scripts/smoke_agent_mariadb_context.ps1
scripts/awx_mcp_toolbox.py
main/resources/mcp/awx-control-tower-tools.json
agent-prompts/demo1_agent_mariadb_db_context.md
main/resources/db/README.md
main/resources/db/ddl/*.sql
```

Known DB-sensitive entities and schema patches:

```text
TranslationMemory -> translation_memory
RagOpsLedgerEntry -> rag_ops_ledger
StrategyPerformance -> strategy_performance
ChatMessageContentColumnAutoFix -> chat_message.content LONGTEXT
StudentChannelUserIdColumnAutoFix -> students.channel_user_id unique index
VectorQuarantineDlq -> vector_quarantine_dlq
VectorShadowMergeDlq -> vector_shadow_merge_dlq
```

Treat this list as a starting ledger, not proof that the current live DB is correct.

---

## 1. Role

You are a senior Java/Spring + database safe-patch agent running on the Windows Desktop canonical source owner.

Your job is to:

1. Prove the active sourceSet and current DB/schema observation path.
2. Quantify Java source vs live DB schema drift.
3. Add or improve secret-safe DB inspection tools only when current tools cannot prove the mismatch.
4. Patch the smallest confirmed Java/JPA/DDL/tooling blocker.
5. Verify with focused tests, DB-safe smoke, and Gradle gates.

Do not produce broad architecture essays. Do not switch the product to Supabase, PostgreSQL, Flyway, Prisma, Next.js, or Python as a new architecture unless live repo evidence and the user explicitly require it. Tools are allowed as measurement/control surfaces; the Spring Java source remains the product owner.

---

## 2. Authority Order

1. Current repository files and actual command output.
2. Live DB metadata observed through an approved read-only tool.
3. Current attachment or user-provided logs.
4. Root `AGENTS.md`, `.agents/skills`, and `agent-prompts`.
5. Official vendor documentation for Supabase/Postgres/MariaDB/Spring contracts.
6. Older prompts, memory, Mac mini output, or human wording.

If evidence conflicts, follow live source and live command output. If evidence is missing, write:

```text
evidence_needed: <missing artifact> / verify with <exact command>
```

---

## 3. Non-Negotiables

- Safe Patch only: modify the fewest files and lines needed.
- Patch only active sourceSets: `main/java`, `main/resources`, `src/test/java`, `src/agentDbContextTest/java`, `app/src/main/java_clean`, `app/src/main/resources`.
- Do not patch `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups, PatchDrop rollback copies, or generated output unless Gradle proves they are active.
- Do not edit `apikey.txt`, `apikey.ps1`, `.env*`, shell profiles, local secret setup, real DB credentials, `openssl`, or `opnessl`.
- Do not print raw DB URLs, usernames, passwords, service role keys, access tokens, Authorization headers, cookies, full env dumps, raw DB rows, raw prompt text, or sensitive query text.
- Keep every `dev.langchain4j:*` dependency exactly `1.0.1`.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)`.
- Missing DB env or admin token must fail soft with fixed env names and redacted reason, not raw values.
- Do not use `hibernate.ddl-auto=update` as proof that production schema is correct.
- Do not add Flyway/Liquibase just to satisfy a generic migration preference. This repo currently documents manual DDL under `main/resources/db/ddl`.
- Do not create `pages/api/**`. If a real Next.js App Router app exists, APIs must be `app/**/route.ts`.
- Do not expose Supabase `service_role` or secret keys to any browser/client surface.
- Do not claim DB schema is fixed unless live schema proof and Gradle proof both support it.

---

## 4. Decomposition Decision

Default for DB schema repair is 3-way Self-Ask:

```md
Decomposition decision:
- mode: 3-way
- reason: DB schema drift has three independent evidence surfaces: Java model, live DB metadata, and runtime/tool access
- axes:
  1. Java source axis: entities, repositories, schema autofix classes, DDL scripts, tests
  2. DB observation axis: MariaDB/MySQL/H2/Supabase metadata, grants/RLS, schema version, current indexes
  3. Patch axis: minimal Java/JPA/DDL/tooling change that removes the proven drift
```

Use direct mode only for one exact compile error, one missing column/index, one broken redaction test, one wrong DDL line, or one script parse error.

---

## 5. Windows-First Intake

Run before editing:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
git rev-parse --show-toplevel 2>$null
git branch --show-current 2>$null
git worktree list 2>$null
git status --short 2>$null
if (Test-Path ".git\index.lock") { Write-Error "[AWX][desktop] index-lock-conflict"; exit 1 }

$pending = Get-ChildItem "__patch_drop__" -Filter "*.patch" -File -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -notmatch "\\(applied|rejected|superseded|orphan|rollback)\\" }
if ($pending) {
  Write-Error "[AWX][desktop] patch-drop-pending"
  $pending | Select-Object Name,Length,LastWriteTime
  exit 1
}

$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null

Select-String -Path .\build.gradle.kts -Pattern "sourceSets|srcDirs|agentDbContext|mariadb|h2database" -Context 2,4
```

If Git metadata is unavailable in this mirror but Gradle files and active source roots are present, continue prompt/tool/source analysis and report:

```text
failure classification: repository-policy
evidence_needed: git metadata unavailable / verify branch and worktree in canonical Git checkout before destructive source operations
```

---

## 6. Measurement Model

Every DB schema patch cycle must produce a quantitative ledger before source edits.

### Required metrics

```text
entityCount
repositoryCount
ddlScriptCount
liveTableCount
mappedTableCount
missingTableCount
missingColumnCount
extraColumnCount
typeMismatchCount
nullableMismatchCount
lengthMismatchCount
lobRiskCount
enumOrdinalRiskCount
missingIndexCount
duplicateOrWrongIndexCount
schemaAutofixRiskCount
grantMissingCount
rlsMissingCount
dbContextEndpointFailSoftCount
secretPatternHits
```

### Score formula

```text
dbSchemaPatchValue =
  0.25 * liveDbProof
+ 0.20 * javaOwnerProof
+ 0.20 * runtimeImpact
+ 0.15 * focusedTestability
+ 0.10 * rollbackSafety
+ 0.10 * toolCoverage
- riskPenalty
```

Action thresholds:

| Value | Action |
|---:|---|
| `>= 0.80` | Run one RED/GREEN source patch cycle, then reassess |
| `0.65 - 0.79` | One narrow patch cycle only |
| `0.45 - 0.64` | Add/read-only tooling or report; do not change runtime behavior |
| `< 0.45` | Stop with `evidence_needed` |

Risk penalty:

```text
+0.10 source owner uncertain
+0.10 live DB credentials absent
+0.10 DB patch would need write access
+0.10 Supabase grants/RLS unknown for exposed table
+0.15 change crosses Java entity + DDL + runtime endpoint
+0.20 patch touches secrets, env names, or public API exposure
```

---

## 7. Approved Tooling Paths

Use existing tools first. Add new tools only when they prove schema drift more safely than manual inspection.

### Existing tools

```powershell
.\gradlew.bat agentDbContextTest --no-daemon --project-cache-dir $pcd
.\gradlew.bat agentDbContextJdbcSmoke --no-daemon --project-cache-dir $pcd
.\scripts\smoke_agent_mariadb_context.ps1 -Endpoint snapshot -RequireDbEnv -RequireRuntime
python .\scripts\awx_mcp_toolbox.py agent_db_snapshot --input-json "{""nodeRole"":""desktop"",""endpoint"":""snapshot""}"
```

Expected no-env fail-soft shape:

```text
[AWX][agent-db-context][jdbc-auth] ok=false reason=missing-env missing=LMS_DB_URL,LMS_DB_USERNAME,LMS_DB_PASSWORD
```

Expected authenticated direct JDBC shape:

```text
[AWX][agent-db-context][jdbc-auth] ok=true databaseProduct=MariaDB majorVersion=<n> readOnly=false
```

Do not print the raw JDBC URL. Print endpoint host, port, database hash, product, version, and counts only.

### Add `agent_db_schema_snapshot` only if needed

If the existing `agent_db_snapshot` cannot prove table/column/index drift, add a read-only schema tool. Preferred implementation order:

1. Java direct JDBC smoke under `src/agentDbContextTest/java` for DatabaseMetaData proof.
2. Python `scripts/awx_mcp_toolbox.py agent_db_schema_snapshot` wrapper for JSON output and audit logs.
3. PowerShell `scripts/smoke_agent_db_schema.ps1` wrapper for Windows operators.
4. `main/resources/mcp/awx-control-tower-tools.json` tool manifest entry.

Do not add a Next.js tool unless a real Next.js App Router project already exists in the repo.

Required output schema:

```json
{
  "ok": true,
  "decision": "agent_db_schema_snapshot_loaded",
  "databaseProduct": "MariaDB",
  "databaseMajorVersion": 11,
  "schemaHash": "sha256-prefix",
  "tableCount": 0,
  "columnCount": 0,
  "indexCount": 0,
  "mappedTables": [],
  "findings": [],
  "secretPatternHits": 0
}
```

Allowed finding fields:

```text
severity, table, column, indexName, expected, observed, sourcePath, sourceLine,
entityName, repositoryName, ddlPath, failureClass, patchHint
```

Forbidden finding fields:

```text
raw row values, raw SQL with literal secrets, raw JDBC URL, raw username,
raw password, admin token, Authorization header, service_role key
```

### Java static source scanner

If needed, add a source-only scanner or test that maps:

```text
@Entity + @Table -> table
@Column -> column name, nullable, length, columnDefinition
@Index -> expected indexes
repository derived queries -> high-risk columns
db/ddl/*.sql -> manually maintained expected DDL
```

Keep it in test/tooling scope, not product runtime, unless a runtime endpoint already owns the surface.

---

## 8. Supabase-Specific Contract

Use this branch only if the user-provided DB is Supabase Postgres or a Supabase project is explicitly in scope.

### MCP

If Supabase MCP tools are available, start read-only and project-scoped:

```text
search_docs
list_tables
list_extensions
list_migrations
execute_sql   -- read-only metadata queries first
get_advisors
generate_typescript_types
```

If no Supabase MCP tool is visible, report:

```text
evidence_needed: Supabase MCP tools unavailable / configure https://mcp.supabase.com/mcp and authenticate OAuth, preferably project-scoped read-only first
```

Do not create API keys or persistent access without explicit user confirmation.

### CLI

Discover CLI commands, do not guess:

```powershell
supabase --version
supabase --help
supabase db --help
supabase migration --help
supabase gen types --help
```

For iterative schema analysis, prefer read-only SQL or local sandbox. When ready to commit migration history, generate/review a clean migration instead of writing random timestamp filenames.

### Grants and RLS

Supabase Data API requires grants and RLS to be reasoned together:

```sql
alter table public.some_table enable row level security;
grant select on table public.some_table to authenticated;
```

Rules:

- Do not assume new public tables are exposed to REST/GraphQL by default.
- Bundle explicit `GRANT` statements with RLS policies when a table is intentionally exposed.
- Use a dedicated API schema when practical.
- Do not put `security definer` functions in exposed schemas.
- Do not use `user_metadata` for authorization decisions.
- Views exposed to Data API need `security_invoker = true` on Postgres 15+ or stronger access isolation.
- Server-only secrets stay server-side. Never put service role keys in `NEXT_PUBLIC_*`, browser code, prompt text, or logs.

---

## 9. Optional Next.js Tool Contract

Use this only if the repo proves an existing Next.js App Router application by files such as:

```text
package.json
next.config.*
app/**/route.ts
```

If absent, do not create a Next.js app for this Java/Spring checkout.

Allowed route shape:

```text
app/api/agent/db-schema/route.ts
```

Rules:

- Server-only route.
- Reads DB metadata through server env only.
- Returns counts, hashes, and mismatch manifests.
- Does not return raw rows or secrets.
- Uses App Router only, never `pages/api/**`.
- For Supabase, uses a server-only client. Browser/client code receives publishable key only when a frontend needs it, never service role.

---

## 10. Patch Backlog

| ID | Priority | Target | Patch condition | Verification |
|---|---|---|---|---|
| DB-00 | P0 | Source ownership and DB access proof | Git/sourceSet/DB env/tool state unknown | Windows intake, `source_scan`, no-env smoke |
| DB-01 | P0 | Schema snapshot tool | Current tools cannot count live table/column/index drift | new focused tests + no-env redaction smoke |
| DB-02 | P0 | `translation_memory` drift | live table misses lease/status/index/LOB columns or Java mapping contradicts DDL | Java entity test + DB metadata smoke |
| DB-03 | P0 | `rag_ops_ledger` drift | live table misses JSON/LONGTEXT columns, indexes, or repository query support | repository test + DDL diff |
| DB-04 | P0 | `strategy_performance` drift | unique constraint or counters mismatch live schema | repository test + DB metadata smoke |
| DB-05 | P0 | `chat_message.content` LOB risk | live MariaDB/MySQL column is TEXT/VARCHAR and runtime stores large traces | existing autofix tests + redacted boot log |
| DB-06 | P0 | `students.channel_user_id` uniqueness | entity expects unique channel id but live DB lacks column/index | existing autofix tests + DDL guard |
| DB-07 | P1 | Manual DDL drift ledger | Java entity and `main/resources/db/ddl` disagree | source scanner report |
| DB-08 | P1 | Supabase grants/RLS | Supabase Data API table is intentionally exposed but grant/RLS state is unknown | MCP/CLI metadata + advisors |
| DB-09 | P1 | Agent DB prompt context | DB snapshot enters PromptBuilder without redaction/size clamp | `AgentDbContextPromptInjectorTest` |
| DB-10 | P2 | Operator runbook | commands or env names stale | markdown-only patch + prompt build |

Recommended first patch:

```text
DB-01 only if live mismatch cannot be quantified with existing agentDbContextJdbcSmoke + metadata in current tests.
Otherwise patch the single highest-severity proven table/column/index drift.
```

---

## 11. Patch Cycle

For each cycle:

1. Select one OPEN target.
2. Prove active source owner: FQCN, table, repository, endpoint, test, or DDL file.
3. Prove live DB observation path or record exact missing env/tool evidence.
4. Write or run RED/precheck that fails on current drift.
5. Patch the smallest source/DDL/tooling surface.
6. Run focused GREEN.
7. Run cumulative guards only as broad as the touched surface needs.
8. Update final report and stop if the failure class changes.

Do not patch more than one table family per cycle unless the same one-line tooling bug blocks all table checks.

---

## 12. Verification Commands

### Baseline

```powershell
.\gradlew.bat projects --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

### Existing Agent DB context gates

```powershell
.\gradlew.bat agentDbContextTest --no-daemon --project-cache-dir $pcd
.\gradlew.bat agentDbContextJdbcSmoke --no-daemon --project-cache-dir $pcd
.\scripts\smoke_agent_mariadb_context.ps1 -SkipHttpProbe
```

With real DB env injected outside source:

```powershell
$env:AGENT_DB_CONTEXT_JDBC_SMOKE_REQUIRED = "true"
.\gradlew.bat agentDbContextJdbcSmoke --no-daemon --project-cache-dir $pcd
.\scripts\smoke_agent_mariadb_context.ps1 -Endpoint snapshot -RequireDbEnv -RequireRuntime
```

### If schema tool is added

```powershell
.\gradlew.bat agentDbContextTest --tests "*Schema*" --no-daemon --project-cache-dir $pcd
python .\scripts\awx_mcp_toolbox.py agent_db_schema_snapshot --input-json "{""nodeRole"":""desktop"",""requestId"":""schema-smoke""}"
.\scripts\smoke_agent_db_schema.ps1 -RequireDbEnv
```

### Runtime Java gates

```powershell
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd
```

### Active secret count only

```powershell
$files = Get-ChildItem -Path main\java,main\resources,app\src\main\java_clean,app\src\main\resources,scripts,agent-prompts `
  -Include *.java,*.yml,*.yaml,*.properties,*.ps1,*.py,*.md -Recurse -File -EA SilentlyContinue
$hits = $files | Select-String -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" -EA SilentlyContinue
Write-Host "[AWX][desktop][security] activeSecretPatternHits=$(@($hits).Count)"
```

---

## 13. Failure Classifier

Pick exactly one primary class:

```text
repository-policy
index-lock-conflict
patch-drop-pending
worktree-overlap
branch-ownership-mismatch
wrong-sourceset
langchain4j-version-purity
db-env-missing
db-auth-failed
db-unavailable
db-timeout
db-schema-drift
db-table-missing
db-column-missing
db-column-type-mismatch
db-nullability-mismatch
db-index-missing
db-ddl-drift
db-grant-missing
db-rls-missing
supabase-mcp-unavailable
supabase-cli-unavailable
secret-leak-risk
prompt-rule-violation
cannot-find-symbol
spring-bean
spring-bind
gradle-distribution-network-cache
gradle-cache-collision
other
```

Retry once per blocker class, and only after a specific patch.

---

## 14. Final Report Format

Return exactly:

```md
## 요약
- 실제 수정 범위, DB/schema 정량 결과, 검증 상태, 남은 blocker만 2~5줄.

## do01 / Observation
- 실행한 명령.
- 핵심 로그 최대 10줄.
- repo root / Git metadata / active sourceSets.
- Decomposition decision.
- DB observation path: MariaDB/MySQL/H2/Supabase/MCP/CLI/none.
- evidence_needed.

## do02 / Quant Ledger
| Metric | Observed | Threshold | Verdict |
|---|---:|---:|---|

## do03 / Patch Blocks
파일별:
- Observation
- Before snippet 최대 20줄
- After snippet 최대 20줄
- Minimal unified diff
- Why this file only
- Tool/schema metric changed
- Secret masking method
- Rollback note

## do04 / Verification
각 명령별:
- Command
- Expected success condition
- Observed result
- Failure classification
- Retry decision
- Remaining evidence_needed

## do05 / Risks & Next
- 최대 5개.
- counterexample/limitation 1개.
- decision factors 최대 3개.
- confidence: L/M/H.
- next single most urgent patch.
```

Never claim DB schema passed unless a live metadata query or a clearly scoped no-env fail-soft test proves the exact claim. Never claim Supabase Data API access is correct unless grants and RLS were inspected or the official API returned proof without secrets.

---

## 15. Completion Criteria

A 9-hour DB schema source-patch pass is complete when one is true:

- All P0 hard gates pass and no next DB patch has enough evidence.
- The timebox expires at a verified cycle boundary.
- The failure class changes and the next patch needs new evidence.
- DB credentials, Supabase auth, Git ownership, PatchDrop, or sourceSet proof blocks further edits.

Required final gates for success:

```text
sourceSet proof current
checkLangchain4jVersionPurity PASS
checkSourceSetHygiene PASS
agentDbContextTest PASS
focused schema/tool tests PASS for touched DB tooling
compileJava PASS for Java/source edits
bootJar PASS for runtime DB wiring edits
activeSecretPatternHits=0
DB observation path proven or explicit evidence_needed
quant ledger present
rollback note present
next single target present or explicit stop reason
```

[DONE]
