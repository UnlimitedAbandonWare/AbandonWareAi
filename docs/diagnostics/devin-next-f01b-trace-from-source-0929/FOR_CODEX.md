# FOR_CODEX — NEXT contract handoff (F01B ∥ TRACE)

Contract: `DEMO1-DEVIN-NEXT-F01B-TRACE-FROM-SOURCE-READ-20260929`. Devin scope:
inventory + script smoke re-verify + this handoff. **Product Java/UI diff = 0.**
Measured live 2026-09-29 ~02:39–02:41 UTC (see `00_INVENTORY.md` §5).

## 1. Codex pastes to load (READ ONLY — paths, not bodies)

- `C:\Users\nninn\Downloads\PASTE_CODEX_F01B_NARROW_JDBC_UNDERSTANDING_20260929.txt`
- `C:\Users\nninn\Downloads\PASTE_CODEX_TRACE_DOCK_ALWAYS_ON_20260929.txt`

## 2. Devin gate results (fresh measurements)

| script | exit | verdict | json path |
|---|---|---|---|
| `f01b_schema_gate.py --mode both` | 2 | `GATE0_FAIL` — live-http lane: `awx_jobs`/`awx_job_results`/`admission_key`/`request_fingerprint`/`awx_jobs_admission_key` all ABSENT; file DDL OK | `data/diagnostics/f01b-trace-access-0929/f01b_schema_gate.json` |
| `f01b_schema_gate_probe.py` (assist probe, exit 0/4/5 vocab) | 4 | `GATE0_FAIL_EVIDENCE_NEEDED` (02:03 UTC, observation=complete) | stdout/00_PROBE.md §4 |
| `f01b_tm_probe.py` | 0 | `PRIVATE_TM_SUSPECT` — `JdbcJobService.java` `new DataSourceTransactionManager`; `runtimeProofRequired=true` | `data/diagnostics/f01b-trace-access-0929/f01b_tm_probe.json` |
| `f01b_admission_key_demo.py --demo` | 0 | hash vectors reproducible | `data/diagnostics/f01b-trace-access-0929/f01b_admission_key_demo.json` |
| `f01b_evidence_pack.py` | 3 | scaffold already present (no `--force`) | `data/diagnostics/f01b-trace-access-0929/f01b_evidence_pack.json` |
| `trace_dock_cost_guard.py` | 2 | FAIL `GENERATION_URL_VIA_DEBUG_QUERY` — `chat.js:6892` still assembles via `chatTraceRequestUrl`/`withDebugQuery` | `data/diagnostics/f01b-trace-access-0929/trace_dock_cost_guard.json` |
| `trace_dock_a11y_scan.py` | 2 | FAIL `HIDDEN_ATTR_ON_DOCK` — dock selectors present but hidden-gated | `data/diagnostics/f01b-trace-access-0929/trace_dock_a11y_scan.json` |
| `agent_access_bundle.py` / `--skip-live-db` | 2 | worst-of-children (TRACE gates FAIL) | `data/diagnostics/f01b-trace-access-0929/agent_access_bundle.json` |

unittest companions: **36/36 green** (exit 0).

## 3. Delta since 02:09 baseline (decision-changing)

- Dock markup **landed** in product files between 02:26–02:34 UTC
  (`chat-ui.html:52-61,357-360` `<aside class="trace-dock …" data-testid=… th:if="${chatDiagnosticsEnabled}" hidden>`,
  `chat-trace-ui.js:18-19,39-40`) → a11y scan moved `SELECTORS_ABSENT_PRE_PATCH`(3) →
  `HIDDEN_ATTR_ON_DOCK`(2). If this is your in-flight TRACE work: dock is still
  `hidden` + `chatDiagnosticsEnabled`-gated and stream URL still goes through
  `chatTraceRequestUrl` (chat.js:6892) — both contract FAIL conditions persist.
- If you did **not** make those edits: unrecorded foreign change on TRACE seams —
  check leases before continuing.

## 4. Standing constraints

- `productEnablement: blocked_until_codex` — flags stay OFF:
  `abandonware.understanding.deferred.enabled` / `jobs.enabled-types` flip only after
  your RED→GREEN contract tests.
- `GATE0_FAIL` ⇒ stay F01-A + `evidence_needed` — no enqueue/fallback invention,
  no DDL apply without separate ops approval.
- NEVER: InMemoryJobQueue F01-B store · task_ask · n8n callbacks · Autograde B reopen ·
  F02 scanner refight · commit/push · secrets · editing applied `V20260912*` bodies.
- TRACE PASS ≠ F01-B approval; F01B schema GATE ≠ TRACE done. F01-A KEEP GREEN;
  stay-A until GATE0 + GATE1 both pass.
- a11y browser snapshot = NOT_RUN by these scripts (static only) — real-browser
  proof is a separate Codex step; `NOT_RUN` is an honest report value.
- Shared-edit protocol: `scripts/coop_verify.py` writer-begin/end; `DEFERRED`(10) is
  never PASS — report `APPLIED_PENDING_VERIFICATION` + ticketId.

## 5. Pointers

- Command SSOT: `docs/diagnostics/devin-scripts-f01b-trace-access-0929/FOR_CODEX_SCRIPTS.md`
- F01B evidence packet: `docs/diagnostics/f01b-narrow-jdbc-0929/{README,GATE0_PROBE,FOR_CODEX}.md`
  + `decision.json` (`status=assist_scripts_ready`, `devin_product_java_diff=0`)
- TRACE guardrails: `docs/diagnostics/trace-dock-always-on-0929/{TRACE_COST_GUARD,TRACE_A11Y_NOTES}.md`
- Assist-rails handoff (predecessor): `docs/diagnostics/devin-assist-f01b-trace-rails-0929/FOR_CODEX.md`

## 6. Lease scopes to claim before product edits

- F01B: `JdbcJobService`, `JobConfig`, `JobService`, `ChatWorkflow`,
  `ChatRunExecutionContext`, `ChatRunRegistry`, `ChatApiController`,
  `ChatHistoryService(Impl)`, `UnderstandAndMemorizeInterceptor`,
  `AnswerUnderstandingService`, `GeneralGraphSourceAuthority(Scope)`,
  `MemoryReinforcementService`, `ChatCancellationCommandHandler`,
  `TasksApiController`, `N8nNotifier`, `service/understanding/*`,
  `db/migration/V2026__f01_*` (new files only).
- TRACE: `chat-ui.html`, `chat-trace-ui.js`, `chat.js`, `chat-trace.css`,
  `chat-style.css`, `ChatApiController`, `ChatTraceMetaMessageRestorer`,
  `ChatTraceSnapshotPointerPersister`, `brain-state-ui.js`, `PageController`.

---

NEXT contract WP3 = STOP for Devin. No product edit, no commit/push.
