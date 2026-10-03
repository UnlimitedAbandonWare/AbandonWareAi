---
name: demo1-path-registry
description: Use when a demo-1 task finds, adds, changes, or moves shared paths (lease, journal, var, handoff, worktrees, tools). Resolve by registry key; register before use; moves keep a 14-day alias + moved.jsonl line; FROZEN keys never move. Detail: references/registry-fields.md.
---

# demo1-path-registry

Single rule set for "which path does X live at" on this checkout.

## Do

- Resolve: `python -B scripts/awx_paths.py where <key>` (env → registry →
  old_paths, alias fallback logs to stderr). Python: `from awx_paths import
  resolve`. PowerShell: `. scripts/AwxPaths.ps1; Resolve-AwxPath -Key <key>`.
- Register new shared locations in `configs/agent-paths.yaml` before use.
- Before moving anything: read `docs/agents-rules/DEMO1-PATH-REGISTRY-SSOT.md`
  status grades; MOVE-SAFE requires all five P3 conditions; every move appends
  `data/agent-handoff/_path-moves/moved.jsonl` and leaves a junction/MOVED.json
  alias for 14 days (expiry audit: `awx_paths.py aliases --expired --plan`).

## Do not

- No new `C:\AbandonWare\...` / `C:\Users\nninn\...` literals in code or docs —
  `awx_paths.py check` flags them. Docs use `<repo>\...` / `%USERPROFILE%\...`.
- Never move `FROZEN` keys (lease/journal/hook/product-state roots, toolchain
  anchors, `repo.root`) without explicit human approval.
- Never delete junctions or aliases, even expired ones — report only.

Detail: `references/registry-fields.md` (key schema + full key table).
