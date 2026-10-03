# FOR_CODEX — Devin assist rails landed (F01B ∥ TRACE, unmerged)

Contract: `DEMO1-DEVIN-ASSIST-F01B-TRACE-RAILS-20260929`. Devin scope is **complete**:
probe + templates + thin skills + AGENTS/index pointers. Product Java/UI diff = 0.
Two tracks below stay separate — different folders, prefixes, success criteria.

Verified facts you start from (2026-09-29 LIVE):
- `scripts/f01b_schema_gate_probe.py` exists and ran: `verdict=GATE0_FAIL_EVIDENCE_NEEDED`,
  exit 4, `observation=complete`, lane `live-http` (file H2 locked by running JVM = an
  answer, not a failure). All required items ABSENT.
- Router resolves: `f01b…`/`awx_jobs` → `demo1-f01b-narrow-jdbc-assist`;
  `trace dock`/`always-on` → `demo1-trace-dock-assist`.
- Devin lease `devin-assist-rails-0929` covers only `scripts/f01b_schema_gate_probe.py`
  + `.agents/skills-intent-index.yaml` — no product seams claimed.

---

## FOR_CODEX — Track F01B (`DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929`)

1. Read `C:\Users\nninn\Downloads\PASTE_CODEX_F01B_NARROW_JDBC_UNDERSTANDING_20260929.txt`.
2. Re-probe GATE-0 (read-only):
   `python -B scripts/f01b_schema_gate_probe.py` — exit 0 `GATE0_PASS` /
   4 `GATE0_FAIL_EVIDENCE_NEEDED` / 5 probe-incomplete (not a schema verdict → evidence_needed).
   Optional `--pretty`. Name-collision warning: sibling task
   `devin-scripts-f01b-trace-access-0929` also shipped `scripts/f01b_schema_gate.py`
   (different filename, different exit vocabulary 0/2) — use `f01b_schema_gate_probe.py`
   for the contract §5 0/4/5 semantics.
   Current result: exit 4 — `awx_jobs`, `awx_job_results`,
   `admission_key`, `request_fingerprint`, unique `awx_jobs_admission_key` all **ABSENT**.
3. Fill `docs/diagnostics/f01b-narrow-jdbc-0929.md` — GATE-0 section already has the
   Devin-probed result; GATE-1/2/3 + `decision.json` are your fill-slots.
4. `GATE0_FAIL` ⇒ stay on F01-A + `evidence_needed` — **no enqueue/fallback invention,
   no DDL apply without separate ops approval** (schema-missing DB stays untouched).
5. `GATE0_PASS` alone still does **not** enable product flags —
   `abandonware.understanding.deferred.enabled` / `jobs.enabled-types` flip only after
   your RED→GREEN contract tests pass.
6. Lease scope to claim before editing product files: `JdbcJobService`, `JobConfig`,
   `JobService`, `ChatWorkflow`, `ChatRunExecutionContext`, `ChatRunRegistry`,
   `ChatApiController`, `ChatHistoryService(Impl)`, `UnderstandAndMemorizeInterceptor`,
   `AnswerUnderstandingService`, `GeneralGraphSourceAuthority(Scope)`,
   `MemoryReinforcementService`, `ChatCancellationCommandHandler`, `TasksApiController`,
   `N8nNotifier`, new `service/understanding/*`, new `db/migration/V2026__f01_*`.
7. NEVER: InMemoryJobQueue · task_ask activation · n8n callbacks · Autograde B reopen ·
   F02 scanner refight · commit/push · secrets · silent A-fallback after B enqueue ·
   editing applied `V20260912*` migration bodies.

## FOR_CODEX — Track TRACE (`DEMO1-CODEX-TRACE-DOCK-ALWAYS-ON-20260929`)

1. Read `C:\Users\nninn\Downloads\PASTE_CODEX_TRACE_DOCK_ALWAYS_ON_20260929.txt`.
2. Read guardrails first: `docs/diagnostics/trace-dock-always-on-0929/TRACE_COST_GUARD.md`
   (coupling map + static rg checks) and `TRACE_A11Y_NOTES.md`.
3. Edit only the Codex-contract §4 file table; do not trample F01-B seams.
4. Prove `visible ON ⇒ +0 debug=true/buildSplitPanel/LLM` as RED→GREEN
   (`TraceDockOnDoesNotEnableEagerHtml`-class test).
5. Lease scope to claim: `chat-ui.html`, `chat-trace-ui.js`, `chat.js`,
   `chat-trace.css`, `chat-style.css`, `ChatApiController`,
   `ChatTraceMetaMessageRestorer`, `ChatTraceSnapshotPointerPersister`,
   `brain-state-ui.js`, `PageController` — coordinate if another agent holds them.
6. Do NOT treat TRACE completion as F01-B progress; a11y claims need a real browser
   snapshot (NOT_RUN is a valid answer).

## Deferred-verify memo (both tracks)

If you share-edit files with other writers: reuse `scripts/coop_verify.py`
(writer-begin/heartbeat/end + request/run-once) and `.agents/skills/awx-cooperative-verification`.
**`DEFERRED` (exit 10) is never PASS** — report `APPLIED_PENDING_VERIFICATION` with the
ticketId; do not re-run builds inline; one `run-once` per turn max.
Detail: `docs/diagnostics/coop-verify-0928/FOR_CODEX.md`.

## Appendix — draft follow-up paste (NOT SENT — user/parent approval required)

```text
Devin rails for DEMO1-DEVIN-ASSIST-F01B-TRACE-RAILS-20260929 are landed:
  scripts/f01b_schema_gate_probe.py · docs/diagnostics/f01b-narrow-jdbc-0929.md ·
  docs/diagnostics/trace-dock-always-on-0929/{TRACE_COST_GUARD,TRACE_A11Y_NOTES}.md ·
  .agents/skills/{demo1-f01b-narrow-jdbc-assist,demo1-trace-dock-assist}/SKILL.md ·
  AGENTS.md DEMO1-F01B-NARROW-JDBC-ASSIST + DEMO1-TRACE-DOCK-ASSIST ·
  skills-intent-index intents f01b-narrow-jdbc-assist / trace-dock-assist.
GATE-0 live: GATE0_FAIL_EVIDENCE_NEEDED (awx_* absent). Follow FOR_CODEX.md §Track F01B / §Track TRACE.
```
