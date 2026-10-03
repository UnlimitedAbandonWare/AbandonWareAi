# Agent artifact scope — read-only vs editable (pointer, 2026-09-28)

READ-ONLY diagnostics — never editable source-of-truth, never a fresh work
queue, never completion evidence:

- Performance finding bundles F01–F08: `agent-prompts/ma21in-f01-f07-*`,
  `.grok/rules/ma21in-f01-f07-assist.md`
- AutoGrade run dumps: `data/agent-handoff/codex-autonomy/autograde-*/`
- All REPORT/verify output under `data/agent-handoff/` and `__patch_drop__/`
- Archived agent-prompts content (`agent-prompts/INDEX.md` "Archived" rows,
  `agent-prompts/_archive/`)

Editable scope = only paths named in the active goal/PASTE plus leased
source files.

Enforcement already in place:

- Goal file read/replace is intake, not Done, and preserves the existing
  queue → `scripts/demo1_goal_switch_barrier.py reject-complete`
  (AGENTS.md `DEMO1-CODEX-GOAL-INTAKE-CONTINUE`, `DEMO1-GOAL-SWITCH`).
- Completed-directive cleanup deletes only checkpoint-sealed
  postimage-matching paths →
  `.agents/skills/demo1-completed-directive-cleanup/scripts/cleanup_completed_directives.ps1`;
  product source/tests are unreachable by it.
- Shared status docs update only via `scripts/status_doc.py --expect-sha256`
  or journal append-note — never wholesale overwrite; foreign leases and
  foreign staging stay untouched (AGENTS.md `DEMO1-WORK-LEDGER`).
