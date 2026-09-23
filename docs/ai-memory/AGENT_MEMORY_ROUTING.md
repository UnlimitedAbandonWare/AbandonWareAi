# Agent session/memory routing — verified capabilities only

> Scope: how Codex, Cline, Grok, and Devin/Notebook flows share this repo's
> canonical state without assuming unsupported shared memory.

## Canonical shared state

- `AGENTS.md` — authoritative operating rules.
- `docs/PROJECT_STATUS.md` — only overall-status entry point.
- `docs/ai-memory/Dynamic_RAG_Orchestration_Platform.memory.md` — durable project memory.
- `data/agent-handoff/codex-autonomy/<taskId>/journal.json` — per-task record.
- Current source plus focused verification outrank all memories and old sessions.

## Agent-local stores actually observed

- Codex: `C:\Users\nninn\.codex\sessions\rollout-*.jsonl`, `archived_sessions\`,
  `memories\`, `memories_1.sqlite`, `state_5.sqlite`, `thread_history_1.sqlite`.
  Memory switches are file settings only; runtime effect needs a new thread/restart.
- Cline: `C:\Users\nninn\.cline\data\sessions\`, plus repo bridge
  `.clinerules/00-demo1-cline-bridge.md` and connectors under `.cline/skills/`.
- Grok: `%USERPROFILE%\.grok\sessions\`, `memory\`, `memory-v2\`,
  `active_sessions.json`; project launcher is `Start-Grok-CLI.bat`.
- Devin/Notebook: repo handoff surfaces plus PatchDrop; concurrent sessions are
  target-scoped under `AGENTS.md`.

## Not assumed

- No shared cloud/project memory API across all four agents is assumed.
- No automatic ingestion of `_rescue` or old reports into retrieval is assumed.
- No app-internal delete API is assumed for Cline/Grok/Claude local sessions.
  Their cleanup stays proposal plus in-app action.

## Start-of-task checklist

1. Read canonical shared state first.
2. List active repo journals; inspect only in-scope records.
3. Inspect live targets; ignore stale handoffs and old success claims.
4. Record new work in the task's own journal and checkpoint cycle.
