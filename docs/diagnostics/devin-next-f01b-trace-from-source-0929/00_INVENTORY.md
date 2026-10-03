# 00_INVENTORY — NEXT contract WP0 (F01B ∥ TRACE, inventory vs LIVE)

- Contract: `DEMO1-DEVIN-NEXT-F01B-TRACE-FROM-SOURCE-READ-20260929` — **Devin rails/handoff only**
- Probed: 2026-09-29 ~02:39 UTC, LIVE root `C:\AbandonWare\demo-1\demo-1\src`
- Agent / taskId: `devin` / `devin-next-f01b-trace-from-source-0929-040560bc`
- Dual tracks stay unmerged: F01B = `DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929`,
  TRACE = `DEMO1-CODEX-TRACE-DOCK-ALWAYS-ON-20260929`
- **Product `main/java` + `main/resources` (static/templates) diff this session: 0 lines.**

## 1. MUST-BUILD 8종 — 전부 OK (SCRIPTS contract already landed)

SCRIPTS task `devin-scripts-f01b-trace-access-0929-ef9d07cb` (closed verified 02:13 UTC)
delivered all MUST-BUILD items. WP1 = re-verify only, no re-implementation.

| mtime UTC | bytes | path | WP0 verdict |
|---|---|---|---|
| 09-29 02:00 | 12619 | scripts/f01b_schema_gate.py | OK |
| 09-29 02:01 | 5117 | scripts/f01b_tm_probe.py | OK |
| 09-29 02:06 | 9902 | scripts/f01b_admission_key_demo.py | OK |
| 09-29 02:08 | 7233 | scripts/f01b_evidence_pack.py | OK |
| 09-29 02:07 | 8745 | scripts/trace_dock_cost_guard.py | OK |
| 09-29 02:09 | 7232 | scripts/trace_dock_a11y_scan.py | OK |
| 09-29 02:04 | 5871 | scripts/agent_access_bundle.py | OK |
| 09-29 02:03 | 9704 | scripts/f01b_schema_gate_probe.py | OK (assist-rails sibling; exit vocab 0/4/5 — distinct from f01b_schema_gate.py 0/2/3) |

Test companions (unittest 36/36 green @02:41 UTC, exit 0):

| mtime UTC | bytes | path |
|---|---|---|
| 09-29 02:05 | 5778 | scripts/test_f01b_schema_gate.py |
| 09-29 02:05 | 3279 | scripts/test_f01b_tm_probe.py |
| 09-29 02:06 | 4557 | scripts/test_f01b_admission_key_demo.py |
| 09-29 02:06 | 3719 | scripts/test_f01b_evidence_pack.py |
| 09-29 02:07 | 4471 | scripts/test_trace_dock_cost_guard.py |
| 09-29 02:07 | 3857 | scripts/test_trace_dock_a11y_scan.py |
| 09-29 02:08 | 5595 | scripts/test_agent_access_bundle.py |

## 2. REUSE / rail inventory (OK)

`db_agent.py` (09-28), `db_gap_scanner.py` (07-12), `mgain_trace_smoke.py` (09-26),
`agent_scope_lease.py`, `coop_verify.py`, `codex_work_checkpoint.py`,
`work_journal.py`, `status_doc.py`, `agent_preflight.py`,
`devin_task_orchestrate.py`, `conditional_local_git.py` — all present, none cloned.

DDL files (file evidence ≠ applied schema):
`V20260912__durable_jobs.sql` (sha256 `b575731f…`) · `V20260912_03__job_idempotency.sql`
(sha256 `784b9c76…`).

Diagnostics/docs rails: `devin-scripts-f01b-trace-access-0929/{00_INVENTORY,FOR_CODEX_SCRIPTS}.md`,
`f01b-narrow-jdbc-0929.md` + `f01b-narrow-jdbc-0929/{README,FOR_CODEX,GATE0_PROBE,decision.json}`,
`trace-dock-always-on-0929/{TRACE_COST_GUARD,TRACE_A11Y_NOTES}.md`,
`devin-assist-f01b-trace-rails-0929/{00_PROBE,FOR_CODEX}.md` — all present.

Skills SSOT: `demo1-f01b-narrow-jdbc-assist`, `demo1-trace-dock-assist` SKILLs +
`.agents/skills-intent-index.yaml` intents resolve (router check this session:
`f01b…` → `demo1-f01b-narrow-jdbc-assist` + optional `demo1-db-agent-cli`).

## 3. Product seams — read-only inventory (sha/mtime only; NO edits by Devin)

| mtime UTC | bytes | path | note |
|---|---|---|---|
| 09-12 05:32 | 16918 | main/java/com/example/lms/jobs/JdbcJobService.java | private `DataSourceTransactionManager` observed (tm_probe `PRIVATE_TM_SUSPECT`) |
| 09-12 02:49 | 975 | main/java/com/example/lms/config/JobConfig.java | |
| 09-28 12:35 | 716777 | main/java/com/example/lms/service/ChatWorkflow.java | F01-A KEEP; not touched |
| 09-29 02:16 | 286034 | main/java/com/example/lms/api/ChatApiController.java | mtime after SCRIPTS close — foreign/Codex WIP |
| 09-29 02:34 | 45257 | main/resources/static/js/chat-trace-ui.js | dock selectors now present (see §5) |
| 09-29 02:34 | 332967 | main/resources/static/js/chat.js | coupling site moved → line 6892 |
| 09-29 02:34 | 30523 | main/resources/templates/chat-ui.html | dock markup landed (hidden-gated) |
| 09-29 02:26 | 3704 | main/resources/static/css/chat-trace.css | |
| 09-29 02:26 | 64453 | main/resources/static/css/chat-style.css | |
| 06-26 08:49 | 4016 | main/java/com/abandonware/ai/agent/job/InMemoryJobQueue.java | F01-B FORBIDDEN store |

`bin/` `build/` `data/agent-handoff/*/preimages/` are not LIVE source.

## 4. Lease state at entry (live)

- `source_edit_session.ps1 -Action status`: active 0, corrupt 0, expired 1
  (`clean-primitive-debug-ai-impl-0926` → `read_rag_debug_trail*` — unrelated scope).
- Goal-switch barrier `switch --agent devin`: `allowedOpen=true`, owned stale claim
  `coop-verify-rails-0928` closed superseded; foreign journals/leases untouched.
- No Devin source lease claimed: this contract writes docs/diagnostics only.

## 5. WP1 smoke re-verify (02:39–02:41 UTC)

| command | exit | result |
|---|---|---|
| `agent_access_bundle.py --skip-live-db` | 2 | F01B_SCHEMA=0 TM=0 KEYS=0 PACK=3(existing,no --force) TRACE_COST=2 TRACE_A11Y=2 |
| `agent_access_bundle.py` (live) | 2 | F01B_SCHEMA=2 GATE0_FAIL live-http (awx_* ABSENT) · TRACE_COST=2 · TRACE_A11Y=2 |
| `f01b_schema_gate.py --mode both --json` | 2 | `GATE0_FAIL` — file OK + live ABSENT; `productEnablement=blocked_until_codex` |
| `trace_dock_cost_guard.py --json` | 2 | FAIL `GENERATION_URL_VIA_DEBUG_QUERY` — `chat.js:6892` via `chatTraceRequestUrl`/`withDebugQuery` |
| `trace_dock_a11y_scan.py --json` | 2 | FAIL `HIDDEN_ATTR_ON_DOCK` — dock selectors now EXIST (`chat-ui.html:52-61,357-360`; `chat-trace-ui.js:18-19,39-40`) with `hidden`/`th:if=chatDiagnosticsEnabled` gating |
| unittest 7 companions | 0 | Ran 36 tests OK |

**Delta vs 02:09 baseline:** TRACE_A11Y moved PARTIAL(3) `SELECTORS_ABSENT_PRE_PATCH`
→ FAIL(2) `HIDDEN_ATTR_ON_DOCK` — dock markup landed in product files
(mtime 02:26–02:34) but is hidden-gated and still debug-coupled. TRACE_COST still FAIL
(coupling line moved 6882→6892). Both FAILs are Codex-track findings, not Devin fixes.

JSON artifacts: `data/diagnostics/f01b-trace-access-0929/*.json` (refreshed by smoke).

## 6. Declarations

- Product `main/java` + `main/resources` diff = **0** this session.
- secrets/DSN/token values printed = 0; commit/push = 0; task_ask/n8n/InMemoryJobQueue/
  AutogradeB/F02 touched = 0; DDL apply = 0; `.windsurf`/`.devin` skill clones = 0.
- F01B GATE result ≠ TRACE verdict; tracks reported separately.
