---
name: demo1-project-root
description: Use when a session starts, cwd looks wrong (e.g. resolves to the user home), or paths must be anchored — lock Project Root to <repo>
---

# demo1-project-root

## Project Root (SSOT)

```
<repo>
```

Every relative path in AGENTS.md, `.agents/skills`, `scripts/`, `main/java`,
`Start-RAG.bat` resolves from this root. It is the default for shell cwd,
greps, patches, Gradle, and Start-RAG — for manual sessions and auto-created
Grokbot/Codex/Devin chats alike.

## Steps

1. At task start (or when cwd looks wrong) run
   `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/devin_cwd_doctor.ps1 -Check`
   — it reports workspace folders resolving to `$HOME`, bad
   `terminal.integrated.cwd`, and canonical `.code-workspace` drift.
2. If C1 reports a home-folder workspace entry, rerun with `-Fix` (backs up
   `workspace.json`, removes the home entry, exit 0 when clean).
3. Re-anchor: `Set-Location -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src'`
   and keep that prefix on later commands (PS 5.1: `;` separators, no `&&`).

## Rules

- Auto-created sessions do **not** re-derive the root from launch cwd — keep
  this path unless the user names another.
- `%USERPROFILE%\.codex\attachments\*`, Downloads, and a ZIP `main/` are
  read-only input sources, never the product root.
- Do not pick `C:\AbandonWare`, `C:\AbandonWare\demo-1`, or
  `...\demo-1\demo-1` as the code root unless the user explicitly redirects.
- AGENTS.md SSOT section: `DEMO1-PROJECT-ROOT`. Grokbot one-liner:
  `agent-prompts/grokbot-default-project-root.md`.

## Verify

- `Get-Location` equals the root; `devin_cwd_doctor.ps1 -Check` exits 0.
- Wrong-root symptom seen in this repo: Devin workspace `folders` re-added
  `%USERPROFILE%` — treat recurrence as fixable via `-Fix`, not a rebuild.

## Output

Report the locked root + doctor exit code; if a foreign agent rewrote the
workspace, note the restored `folders` list. No product-source changes ever.

## Hard stops

- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
