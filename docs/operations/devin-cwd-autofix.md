# devin-cwd-autofix

Repair Devin Local sessions that start in `C:\Users\nninn` instead of the
demo-1 project root. Owner: work journal `devin-cwd-autofix-658af8fa`
(Grok directive 2026-10-02).

## Root cause

Devin keeps one `workspace.json` per window under
`%APPDATA%\Devin\Workspaces\<id>\`. Workspace `1790732690335` was saved with a
relative folder path `../../../../..` which resolves to `C:\Users\nninn`, so the
window's root became the home directory and repo rules under
`src\.windsurf\rules` were never loaded. Windows are prewarmed — a fresh window
can reuse or recreate such an entry, which is why manual settings edits appear
to "reset".

## What changed (5 locations)

| # | Location | Change | Backup |
|---|----------|--------|--------|
| 1 | `%APPDATA%\Devin\Workspaces\*\workspace.json` | `folders` rewritten to `[{"name":"src","path":"C:/AbandonWare/demo-1/demo-1/src"}]`; other keys preserved | `*.bak-20261002-HHmm` beside each file |
| 2 | `%APPDATA%\devin\User\settings.json` | added `terminal.integrated.cwd = C:\AbandonWare\demo-1\demo-1\src` | `settings.json.bak-20261002-1050` |
| 3 | `~\.codeium\windsurf\memories\global_rules.md` | appended `DEMO1-CWD-GUARD` block (7 lines) | `global_rules.md.bak-20261002-1050` |
| 4 | `C:\AbandonWare\demo-1\demo-1\demo1-src.code-workspace` | created — single `src` folder + `terminal.integrated.cwd` | new file |
| 5 | `C:\AbandonWare\demo-1\demo-1\Open-Devin-demo1.bat` | created — runs doctor `-Fix` then opens the workspace | new file |

The currently-open window `1790732690335` was left **STAGED** (untouched) so
the live session is not force-reloaded; rerun with `-Fix -IncludeActive` after
closing it.

## Commands

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\devin_cwd_doctor.ps1 -Check   # exit 2 = drift
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\devin_cwd_doctor.ps1 -Fix     # repair, backs up first
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\devin_cwd_doctor.ps1 -Fix -IncludeActive  # also fix the open window
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\devin_cwd_doctor.ps1 -Restore <file>.bak-yyyyMMdd-HHmm
```

`-Json` prints machine-readable output with per-file `shaBefore`/`shaAfter`.
Re-running `-Fix` is a no-op once healthy.

## Rollback

1. Restore each `*.bak-20261002-*` via `-Restore`, or copy it back manually.
2. Delete `demo1-src.code-workspace` / `Open-Devin-demo1.bat` if unwanted.
3. The `DEMO1-CWD-GUARD` block is delimited by `<!-- DEMO1-CWD-GUARD -->` /
   `<!-- /DEMO1-CWD-GUARD -->` — remove it or restore the backup.

## User verification

1. Close every Devin window, then run `Open-Devin-demo1.bat`.
2. In the new Devin session run `Get-Location` → expect
   `C:\AbandonWare\demo-1\demo-1\src` (integrated terminal must also open there
   via `terminal.integrated.cwd`).
3. `scripts\devin_cwd_doctor.ps1 -Check` should print `C2/C3/C4 OK` and no
   workspace resolving to `C:\Users\nninn` (exit 0 once the stale window's
   workspace is also fixed or removed).

## Evidence

`docs\diagnostics\devin-cwd-autofix-20261002\` — check-before.json,
fix-run1.json, check-after.json, fix-run2.json, chatjs-sha256-before.txt,
README.md.
