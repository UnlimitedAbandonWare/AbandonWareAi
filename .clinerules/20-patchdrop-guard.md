---
description: PatchDrop bundle intake and shared-source coordination (loads when work touches __patch_drop__).
paths:
  - "__patch_drop__/**"
---

# PatchDrop — load orchestrator procedure first

This rule only activates when a request or open file touches `__patch_drop__` (see `paths`).

- Before ingesting, patching, or deleting anything under `__patch_drop__`: read `.agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md`; for manual/desktop-side drops also `.agents/skills/demo1-patchdrop-manual-default/SKILL.md`.
- `source_edit_session.ps1` / `source-edit-locks/` are the live lease machinery: acquire a target-scoped lease before source writes, and never edit or delete another owner's lock/lease/journal.
- Unknown or stale-looking dirs: inventory first (see `janitor_inventory` via `scripts/awx_mcp_toolbox.ps1`), never bulk-clean.
- Moves/renames count as two targets in `scripts/codex_work_checkpoint.py` cycles.
