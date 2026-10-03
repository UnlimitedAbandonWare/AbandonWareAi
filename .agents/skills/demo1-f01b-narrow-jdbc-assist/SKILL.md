---
name: demo1-f01b-narrow-jdbc-assist
description: >-
  Use when assisting the Codex F01-B narrow JDBC UNDERSTANDING contract (DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929) — Devin rails only: GATE-0 schema probe, evidence template, lease scope, FOR_CODEX handoff. Never product Java/UI.
---

# demo1-f01b-narrow-jdbc-assist

Assist rail for the Codex contract `DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929`.
**Devin measures the gates; Devin never declares pass and never flips product flags.**

## Do

- GATE-0 re-probe (read-only):
  `python -B scripts/f01b_schema_gate_probe.py [--via auto|file|live]`
  exit 0 `GATE0_PASS` · 4 `GATE0_FAIL_EVIDENCE_NEEDED` · 5 probe-incomplete (NOT a schema verdict)
- Evidence packet SSOT: `docs/diagnostics/f01b-narrow-jdbc-0929.md`
  (GATE-0 section is Devin-filled; GATE-1/2/3 stay Codex fill-slots)
- Handoff card: `docs/diagnostics/devin-assist-f01b-trace-rails-0929/FOR_CODEX.md` §Track F01B
- Inventory/gap evidence: `docs/diagnostics/devin-assist-f01b-trace-rails-0929/00_PROBE.md`

## Never

- Edit product Java/UI (JdbcJobService, JobConfig, ChatWorkflow, ChatApiController,
  TasksApiController, N8nNotifier, UnderstandAndMemorizeInterceptor,
  ChatHistoryServiceImpl, `service/understanding/*`, …) — Codex seams
- Apply DDL to the live DB or invent an in-memory fallback when schema is absent
- Wire `InMemoryJobQueue`, activate `task_ask`/n8n callbacks, reopen Autograde B,
  refight F02 scanner, commit/push, or print secrets
- Merge with the TRACE-DOCK contract, or read `GATE0_PASS` as permission to flip
  `abandonware.understanding.deferred.enabled` / `jobs.enabled-types`

## Pinned

- Codex paste (READ ONLY): `%USERPROFILE%\Downloads\PASTE_CODEX_F01B_NARROW_JDBC_UNDERSTANDING_20260929.txt`
- DB lane SSOT: `scripts/db_agent.py` (exit 3 `locked` = JVM holds lmsdb — use
  `--via auto` live fallback; never kill the server or delete the file)
- Work ledger: `$demo1-work-ledger` (journal → checkpoint → verify)
