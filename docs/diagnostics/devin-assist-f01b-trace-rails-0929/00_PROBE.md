# 00_PROBE — Devin assist rails WP0 inventory (F01B ∥ TRACE)

- Contract: `DEMO1-DEVIN-ASSIST-F01B-TRACE-RAILS-20260929` — **Devin ASSIST ONLY**
- Probed: 2026-09-29 ~02:00 UTC, LIVE root `C:\AbandonWare\demo-1\demo-1\src`
- Agent / taskId: `devin-f01b-trace-rails` / `devin-assist-f01b-trace-rails-0929-3525a48a`
- Dual tracks stay unmerged: F01B = `DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929`,
  TRACE = `DEMO1-CODEX-TRACE-DOCK-ALWAYS-ON-20260929`
- **Product `main/java` + UI (`chat.js`/`chat-ui.html`/`chat-trace-ui.js`) diff this session: 0 lines.**

## 1. Path inventory (LIVE re-verified 2026-09-29)

All contract §2 paths exist — 0 MISS.

| mtime UTC | bytes | path | one-line role |
|---|---|---|---|
| 09-24 03:54 | 35657 | scripts/agent_scope_lease.py (+ .ps1) | scope claim/conflict advisory |
| 09-24 03:58 | 47818 | scripts/lease_conflict_autoflow.py (+ .ps1) | stale lease reclaim/quarantine |
| 09-10 09:21 | 13783 | scripts/agent_code_evidence_gate.py | agent code evidence gate |
| 09-23 00:59 | 7173 | scripts/status_doc.py | PROJECT_STATUS row writer |
| 09-27 04:38 | 36828 | scripts/conditional_local_git.py | local-git gate |
| 09-28 14:18 | 39261 | scripts/coop_verify.py | deferred-verify rail (DEFERRED≠PASS) |
| 09-28 14:01 | 58775 | scripts/codex_work_checkpoint.py | preimage/seal/finish/restore |
| 09-26 11:07 | 16039 | scripts/work_journal.py | task journal |
| 09-28 13:08 | 17723 | scripts/agent_preflight.py | task-entry peer/lease signal |
| 07-12 19:05 | 69295 | scripts/db_gap_scanner.py | **static Java @Entity scanner — not a live DB probe** |
| 09-28 03:51 | 47292 | scripts/db_agent.py | lmsdb file-H2 SSOT lane (status/tables/schema/query; exit3=lock) |
| 09-26 13:04 | 21903 | scripts/mgain_trace_smoke.py | per-answer debug-trace static smoke |
| 09-24 06:49 | 20360 | scripts/devin_task_orchestrate.py | multi-seam plan/next/capture |
| 09-24 13:28 | 2649 | scripts/verify_source_evidence_hashes.ps1 | source-evidence hash re-anchor |
| 09-12 02:49 | 1157 | main/resources/db/migration/V20260912__durable_jobs.sql | awx_jobs/awx_job_results DDL (file only) |
| 09-12 05:32 | 446 | main/resources/db/migration/V20260912_03__job_idempotency.sql | admission_key/request_fingerprint + unique idx |
| — | — | .agents/skills/{demo1-devin-source-orchestrator, demo1-agent-code-evidence-gate, demo1-lease-conflict-autoflow, awx-cooperative-verification, demo1-work-ledger, safe-source-edit, compile-verify-smoke, mgain-debug-trace-restore, demo1-artifact-trace-curator}/SKILL.md | all OK |
| 09-28 17:31 | 24804 | docs/diagnostics/max-push-progress-0928.md | F01-A GREEN_SOURCE; F01-B DEFERRED_STORAGE_CONTRACT |
| 09-28 17:31 | 21892 | docs/diagnostics/max-push-b-perf-0928.md | same awx_jobs ABSENT observation |
| 09-27 00:16 | 2045 | docs/diagnostics/trace-porting-assist-rails.md | prior Devin assist rail (reused) |
| 09-27 00:40 | 13132 | docs/diagnostics/trace-porting-baseline.md | Codex WP0 baseline (read-only) |
| 09-28 14:07 | 5809 | docs/diagnostics/coop-verify-0928/FOR_CODEX.md | prior FOR_CODEX pattern (reused) |
| — | — | product seams (JdbcJobService/JobConfig/ChatApiController/chat.js/chat-ui.html/chat-trace-ui.js/chat-trace.css/chat-style.css/InMemoryJobQueue) | all OK — **read-only for Devin** |
| 09-28 13:11 | 15053 | .agents/skills-intent-index.yaml | router SSOT |
| 09-23/09-28 | — | .devin/RULES_SSOT.md + hooks.v1.json | exist — no skill clones written there |

## 2. Codex gate × existing tool — gap table

| Codex gate / seam | existing tool | gap → fill |
|---|---|---|
| F01B GATE-0 awx_jobs + idempotency columns + unique idx | `db_gap_scanner.py` (static Java scan only), DDL files | **전용 read-only schema GATE 프로브 없었음 → `scripts/f01b_schema_gate_probe.py` 채움** |
| F01B GATE-1 shared TM proof | (product tests = Codex) | Devin: evidence template fill-slots only |
| F01B stay-A / evidence_needed reporting | status_doc + diagnostics md | `docs/diagnostics/f01b-narrow-jdbc-0929.md` template 채움 |
| TRACE visible ≠ debug=true | `mgain_trace_smoke.py` + chat_trace contract tests | always-on dock 전용 체크리스트 → `trace-dock-always-on-0929/TRACE_COST_GUARD.md` |
| TRACE a11y (aria-hidden/inert) | `markChatDiagnosticNode` (product seam) | Devin: `trace-dock-always-on-0929/TRACE_A11Y_NOTES.md` + static grep pointers only |
| lease / checkpoint | agent_scope_lease + lease_conflict_autoflow + codex_work_checkpoint | per-track claim scope documented in FOR_CODEX (Devin claims rails files only) |
| coop deferred verify | `coop_verify.py` + `awx-cooperative-verification` | DEFERRED ≠ PASS memo line in FOR_CODEX |

## 3. Lease state at entry (live)

- `source_edit_session.ps1 -Action status -Json`: active 0, corrupt 0, expired 1
  (`clean-primitive-debug-ai-impl-0926` → `read_rag_debug_trail*` paths — unrelated to this scope)
- Target manifest check (`scripts/f01b_schema_gate_probe.py`, `AGENTS.md`,
  `.agents/skills-intent-index.yaml`): `targetConflict.allowed=true`, conflicting 0
- **Devin lease taken**: `devin-assist-rails-0929` → `scripts/f01b_schema_gate_probe.py` +
  `.agents/skills-intent-index.yaml` only. Codex product seams (JdbcJobService, JobConfig,
  ChatWorkflow, ChatApiController, TasksApiController, N8nNotifier,
  UnderstandAndMemorizeInterceptor, ChatHistoryServiceImpl, `understanding/*`, chat.js,
  chat-ui.html, chat-trace-ui.js) are deliberately **not** claimed.

## 4. Live DB observation (read-only — the GATE-0 fact)

Command: `python -B scripts/f01b_schema_gate_probe.py` → exit 4,
`verdict=GATE0_FAIL_EVIDENCE_NEEDED`, `observation=complete` (run 2026-09-29T02:03Z).

- Lane: file JDBC `locked` (Start-RAG JVM holds `var/meta-display-db/lmsdb.mv.db`) →
  `--via auto` live-http fallback (`/api/internal/db/meta`) — lock is an answer, not a failure
- 32 PUBLIC tables present; `awx_jobs`, `awx_job_results`, `awx_understanding_receipts`
  all **ABSENT**; `awx_jobs.admission_key`/`request_fingerprint` and unique index
  `awx_jobs_admission_key` ABSENT with table
- DDL files exist on disk (sha256 `b575731f…`, `784b9c76…`) — **file evidence ≠ applied schema**
- Prior report ("awx_jobs ABSENT") **re-confirmed live**; `SCHEMA_APPLIED` remains NOT_RUN/FAIL

## 5. Declarations

- Product `main/java`, `main/resources/static`, `main/resources/templates` diff = **0**
- secrets/DSN/token values printed = 0; commit/push = 0; task_ask/n8n/InMemoryJobQueue/
  AutogradeB/F02 touched = 0; `.windsurf`/`.devin` skill clones = 0
- Track outputs use different folders/prefixes/success criteria; no merged deliverable
