---
name: demo1-trace-dock-assist
description: >-
  Use when assisting the Codex always-on dual trace dock contract (DEMO1-CODEX-TRACE-DOCK-ALWAYS-ON-20260929-R2) — Devin rails only: F09 cost guard + a11y notes + static check pointers. Separate from mgain per-answer debug-trace; product JS/HTML stays Codex-owned. JE-1 late 401/403/429 is a parallel track.
---

# demo1-trace-dock-assist

Assist rail for the Codex contract `DEMO1-CODEX-TRACE-DOCK-ALWAYS-ON-20260929-R2`
(always-on dual trace dock inside `/chat`).
JE-1 (`DEMO1-CODEX-JEV-LATE-ERROR-20260929`) is a separate track and does not change Jev defaults.
**visible ON ≠ `debug=true`/eager HTML. Do NOT merge with the per-answer mgain
debug-trace skill or the F01-B contract.**

## Do

- Guardrails: `docs/diagnostics/trace-dock-always-on-0929/TRACE_COST_GUARD.md`
  (coupling map + static rg checks) and `TRACE_A11Y_NOTES.md`
  (a11y rules + NOT_RUN honesty)
- Handoff: `docs/diagnostics/devin-assist-f01b-trace-rails-0929/FOR_CODEX.md` §Track TRACE
- Reuse existing rails before adding tools:
  `docs/diagnostics/trace-porting-assist-rails.md`,
  `docs/diagnostics/trace-porting-baseline.md`,
  `scripts/mgain_trace_smoke.py`, `scripts/test_chat_trace_ui_porting.cjs`,
  `src/test/js/chat-trace-ui.test.cjs`, `src/test/js/chat-trace-restore.test.cjs`
- R2 dock ids: `trace-dock-toggle`, `trace-dock-current`, `trace-dock-history`
- Poll `/api/diagnostics/debug/events/page?limit=50` only. HTTP 410 is a cursor gap.
  No `EventSource` and no `/api/diagnostics/debug/events/stream`.
- Non-admin UI is the dock shell plus an explicit no-permission state.

## Never

- Edit `chat.js` / `chat-trace-ui.js` / `chat-ui.html` / `ChatApiController` /
  `chat-trace.css` / `chat-style.css` — product seams are Codex-owned
- Make visible-ON force `stream/sync?debug=true` or `buildSplitPanel` eager HTML (F09 trap)
- Force `enabled()` true. That spreads `debug=true` onto generation requests.
- Route the always-on dock through `markChatDiagnosticNode` (aria-hidden)
- Treat TRACE completion as F01-B progress; store trace/memory bodies or secrets in
  localStorage; claim a11y verified without a real browser snapshot (NOT_RUN allowed)
- Merge this skill into `mgain-debug-trace-restore` — names must stay distinct

## Pinned

- Codex paste (READ ONLY, current): `%USERPROFILE%\Downloads\PASTE_CODEX_TRACE_R2_JEV_LATE_MAX_20260929.txt`
- R1 paste superseded: `%USERPROFILE%\Downloads\PASTE_CODEX_TRACE_DOCK_ALWAYS_ON_20260929.txt`
- Work ledger: `$demo1-work-ledger` (journal → checkpoint → verify)
- Assist packet: `data/agent-handoff/grok-assist-trace-r2-jev-20260929/FOR_CODEX.md`

- 브라우저로 /chat을 시험할 때 모델 선택은 `demo1-codex-browser-agent` / `demo1-test-model-policy`를 따른다.
