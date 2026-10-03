# H2 DDL warnings — classification (D2, 2026-09-28)

Scope: classify the 132 launcher-log exception lines separately from the B02
`agentPromptSearch` repair. **Diagnose-only** — no drop-all, no schema rebuild,
no DB writes performed.

## Evidence

- Log: `var/rag-launcher/20260928-181341-16d0942f/chat-ui-vibe-listener-18180.out.log`
- Verify-RAG classification: `exception-lines=132`, `error-level=132`
- Every one of the 132 lines is a Hibernate `GenerationTarget encountered
  exception accepting command` WARN pair: the WARN line plus its
  `CommandAcceptanceException` stack. That means **66 distinct DDL statements
  failed**, each logged twice -> 132 exception lines.

## Counts by kind (`scripts/classify_h2_ddl_warnings.py`)

| kind | count |
|------|-------|
| `create table` -> "Table already exists" | 32 |
| `create index` -> "Index already exists" | 26 |
| `alter table add constraint` -> "Constraint already exists" | 8 |
| other / non-already-exists | 0 |
| **distinct failed statements** | **66** |

Affected objects are ordinary LMS domain tables (`chat_session`,
`chat_message`, `administrators`, `nova_focus_*`, `attachment_source`,
`rag_ops_ledger`, ...) — no search/RAG/vector tables in the failure set.

## Root cause

`application-desktop-gpu-node.yml` + `application-macmini-control-plane.yml`
run `spring.jpa.hibernate.ddl-auto=update` (env overridable). Hibernate
`update` emits raw `create table` / `create index` / `alter table add
constraint` **without `IF NOT EXISTS`** against the persisted H2 file DB, so
every boot where objects already exist logs WARN+exception pairs. This is
pre-existing schema-update noise, unrelated to B02 chat/search code paths.

## Separation from B02

- B02 patch only touched `ChatApiController.agentPromptSearch` +
  `TraceHtmlBuilder` rendering; it cannot emit Hibernate DDL warnings.
- Same warning class appears in pre-edit launcher trails — consistent with
  a long-standing `ddl-auto=update` artifact, not a regression.

## Action taken

- New read-only diagnostic: `python -B scripts/classify_h2_ddl_warnings.py <log>`
  (or `--latest` for the newest launcher out.log). Parses only; never touches
  the DB or a running JVM.

## Candidate fixes (all intentionally NOT applied)

| option | why not now |
|--------|-------------|
| `ddl-auto=validate` + migration tool | schema versioning decision; out of bounded scope |
| `ddl-auto=none` + manual migration | same; needs owner sign-off |
| drop all tables / rebuild | explicitly forbidden; destructive |

Result: **66 DDL failures, 100% benign "already exists" noise; zero real
schema bugs actionable inside this contract.**
