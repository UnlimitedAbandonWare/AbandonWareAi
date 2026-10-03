# H2 DDL 132 — conflict classification (assist, diagnose-only)

Contract: `DEMO1-DEVIN-AUTOGRADE-B-R3-ASSIST-20260928` task A
Agent: devin · journal: `autograde-b-r3-assist-0928-83a88157` · product diff: 0
Refreshes: `docs/diagnostics/h2-ddl-warnings-20260928.md` (same class, later launcher runs)

## Evidence (사실)

- Log: `var/rag-launcher/20260928-205140-7bed4b7d/chat-ui-vibe-listener-18180.out.log`
  (run started 2026-09-28 20:51-53 KST; JVM PID 10188; `--spring.profiles.active=local,meta-display`).
  Newer boots `20260928-205534-*` and `20260928-211657-*` show the same pattern.
- `python -B scripts/classify_h2_ddl_warnings.py --latest` → `ddlWarnLines=132`,
  `hibernateWarnLines=66` → **66 distinct failed statements**, each emitted as a
  WARN line plus a `CommandAcceptanceException` stack (132 lines total).
- Stack anchor: `GenerationTargetToDatabase.accept` ← `AbstractSchemaMigrator.createTable`
  ← `GroupedSchemaMigratorImpl.performTablesMigration` ← `SchemaManagementToolCoordinator.performDatabaseAction`
  ← `SessionFactoryObserverForSchemaExport.sessionFactoryCreated` — Hibernate
  schema-management **migrate** action during EntityManagerFactory bootstrap.
- 0 non-"already exists" errors in the set.

## Classification

| kind | count | H2 code | sample (condensed) | likely source | repair_owner |
|---|---|---|---|---|---|
| `create table` → already exists | 32 | 42101 | `create table administrators` | Hibernate auto-DDL migrate vs persisted `lmsdb.mv.db` | none |
| `create index` → already exists | 26 | 42111 | index on `chat_message` | same | none |
| `alter table add constraint` → already exists | 8 | 90045 | FK constraint on `chat_message` | same | none |
| other / non already-exists | 0 | — | — | — | — |

Affected objects are ordinary LMS domain tables (`administrators`,
`chat_session`, `chat_message`, `attachment_source`, `nova_focus_*`,
`rag_ops_ledger`, `strategy_performance`, `model_info`, …). No search/vector
tables. **All 66 are benign already-exists noise — zero real schema defects,
zero data risk.**

## Re-run path (사실)

The DDL fires once per Spring boot in the `local,meta-display` lane:
`Start-RAG.bat` / `Start-Meta-Display.bat` → launcher PS1 → `gradlew bootRun` →
`LocalContainerEntityManagerFactoryBean` against
`spring.datasource.url=jdbc:h2:file:./var/meta-display-db/lmsdb;MODE=MariaDB;DATABASE_TO_UPPER=false`
(`application-meta-display.yml:7`). Every Verify-RAG restart reproduces it.

## Effective `hbm2ddl` provenance (추정 — unresolved)

- Declared config: `application.properties:200`
  `spring.jpa.hibernate.ddl-auto=${LMS_JPA_DDL_AUTO:validate}`. The `local` /
  `meta-display` profiles do not override `jpa` (`application-local.*` absent;
  `application-meta-display.yml` has no `jpa` block). `validate` never emits
  DDL — yet observed frames are `GroupedSchemaMigratorImpl` (update family).
- Ruled out this session: JVM args on PID 10188 (profile+ports only);
  User/Machine env (`LMS_JPA_DDL_AUTO`, `SPRING_JPA_*`); `.env` / `shared.env`
  (no JPA keys); `build/desktop-meta-display` + `build/resources` outputs (both
  carry the same `validate` line); Flyway/Liquibase (absent from Gradle);
  `persistence.xml` / `hibernate.cfg.xml` (absent); programmatic EMF/SchemaExport
  in `main/java` (none); `spring.sql.init` (pinned `never` in verification);
  `RuntimeConfigGuard` / `NovaPropertyAliasEnvironmentPostProcessor` (no jpa
  keys); `configs/` (no application config files).
- Remaining candidates: (a) `LMS_JPA_DDL_AUTO=update` exported in the launching
  shell's process env (not observable cross-process here); (b) an override via
  a profile file for a different lane (`application-desktop-gpu-node.yml` /
  `application-macmini-control-plane.yml` both default `update` — inactive in
  this run); (c) a property contributor not yet located.
- `/actuator/env` returned `403 authorization` — auth-blocked, live value
  unread. A one-line effective-config dump in the launcher
  (`SPRING_JPA_HIBERNATE_DDLAUTO` echo) would settle it.
  **Owner: later-codex/human** — out of assist scope.
- `main/resources/db/README.md:3` still claims "Hibernate DDL auto-update in
  the default profile" — stale vs the `validate` pin (doc drift only).
- `RuntimeConfigShadowGuardTest` already asserts no active profile carries
  `ddl-auto=update`, so the declared config tree is intended-clean.

## Actions taken

None — diagnose-only. No DB mutation, no drop/repair, no schema change, no
source edit. Reproduce: `python -B scripts/classify_h2_ddl_warnings.py --latest`.
