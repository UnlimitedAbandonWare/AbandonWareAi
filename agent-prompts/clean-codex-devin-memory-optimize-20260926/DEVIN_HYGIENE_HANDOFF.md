# Devin hygiene handoff — 2026-09-26 (one page)

From Clean (Cline), task `clean-codex-devin-memory-optimize-6114d683`.
Scope: Devin local footprint only. Move-first, never delete in place, no secrets.

## 1. Skills SSOT (verified, keep as is)

- SSOT = `C:\AbandonWare\demo-1\demo-1\src\.agents\skills\` (125 skill dirs).
- Verified absent: `%APPDATA%\devin\skills`, `%USERPROFILE%\.devin\skills`
  (`\.devin` holds only `extensions\` + `argv.json`).
- Rule: never copy/clone skills into AppData or the install path, never add a
  second skills root, never symlink a copy. Devin reads the repo root.

## 2. Cache candidates — close Devin fully first

25 Devin processes were alive at audit time, so nothing was moved here. Close
Devin (and the `devin` CLI), then move each folder to the rescue root, verify a
clean relaunch, and only then delete the moved tree.

| folder (`%APPDATA%\devin\`) | files | MiB | action |
|---|---|---|---|
| `Cache` | 157 | 487.4 | move candidate |
| `CachedData` | 4,382 | 256.2 | move candidate |
| `GPUCache` | 5 | 5.6 | move candidate |
| `Service Worker` | 48 | 0.7 | move candidate |
| `Code Cache` / `blob_storage` | 4 / 0 | ~0 | skip, not worth it |
| `logs` | 718 | 222.0 | **defer** — 11 session dirs, newest 2026-09-26 23:37, none older than 14 days |

Realistic reclaim is ~750 MiB (Cache + CachedData); do not expect the log bulk.

```powershell
$dst='C:\AbandonWare\_rescue\devin-hygiene-20260926\20260927'
New-Item -ItemType Directory -Force -Path $dst | Out-Null
foreach($n in 'Cache','CachedData','GPUCache','Service Worker'){
  Move-Item -LiteralPath "$env:APPDATA\devin\$n" -Destination $dst   # move, not Remove-Item
}
```

## 3. Never touch (all present, name-verified only — contents never printed)

`credentials.toml`, `Local State`, `mcp_config.json` (+ `.pre-edit*.bak`),
`config.json` (+ `.pre-edit.bak`), `Network`, `Session Storage`, `Preferences`,
`Local Storage`, `IndexedDB`, `WebStorage`, `machineid`, `DIPS`, `DIPS-wal`,
`code.lock`, `Workspaces\`, `Backups\`, `User\`. No `.secrets\` reads, no key or
token values in any log, patch, or report.

## 4. Shared memory boundary

- Durable Devin-side facts go to `docs\ai-memory\` plus Devin's own
  `data\agent-handoff\codex-autonomy\<taskId>\journal.json` — never another
  agent's journal.
- Cross-agent state stays repo journals + the peer-signal bridge
  (`agent-prompts\codex-devin-peer-signal-bridge-20260926`).
- No Devin cloud Knowledge dump into the repo, and `C:\AbandonWare\_rescue\codex-quarantine-*`
  payloads are quarantine evidence — not memory, not retrieval input, not to be
  re-injected into `~\.codex\sessions` or any memory store.
- Codex session quarantine result and limits: `docs\ai-memory\CODEX_SESSION_QUARANTINE_20260926.md`.

## 5. Done when

1. Devin process list empty before any move; each moved folder recorded.
2. Devin relaunch verified (skills still resolve from the repo root).
3. Section 3 paths byte-untouched; no secret values anywhere in output.
4. No new skills root, no second memory store, no Knowledge export committed.
