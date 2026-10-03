---
trigger: always_on
---
# demo1-cwd-powershell51

Related: `demo1-hard-constraints.md` (Project Root section) — this file adds
only the drift-recovery procedure, not a second root definition.

- If `$PWD.Path` is `C:\Users\nninn` (or anything outside
  `C:\AbandonWare\demo-1\demo-1\src`) on task entry, the Devin window opened the
  wrong workspace — do not re-derive the root from it. Run
  `Set-Location -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src'` before anything else.
- Never run `git`, `python`, `pip`, or `npm` from `C:\Users\nninn`: they would
  see no repo and produce misleading empty/failure output.
- Commands run via `powershell -NoProfile` — Bash operators (`&&`, `||`),
  `2>/dev/null`, and `shell_flavor=bash` all fail; use `;` and
  `if ($LASTEXITCODE -eq 0) { ... }` instead.
- Self-repair: `powershell -NoProfile -File scripts\devin_cwd_doctor.ps1 -Check`
  reports workspace/settings/rule drift (exit 2 = drift found);
  `-Fix` repairs it with backups, `-Restore <bak>` rolls back.
