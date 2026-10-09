# demo1-devin-local-pin

Use when a new Devin session opened from this checkout lands on Devin Cloud
(Ubuntu) instead of Devin Local, or when the agent pin must be checked, healed,
or temporarily relaxed for intentional Cloud use.

## Cause (verified 2026-10-08, DESKTOP-M5NOV6K)

- `%APPDATA%\devin\User\settings.json` `devin.acp.preferredAgent` decides which
  agent a new tab opens; empty = Devin Local. The app rewrites it with the
  last-picked agent seconds after a new `windsurf-agent-window` workspace is
  created — that is why manual fixes kept reverting.
- `src\.vscode\settings.json` alone cannot pin it: agent windows open via a
  workspace file (`Workspaces\<n>\workspace.json`, `settings:{}`), and the key
  has no declared `scope` (defaults to window scope), so folder-level settings
  are ignored and the user-level value wins.
- Hard pin = two user-settings keys: `preferredAgent: "devin-cli"` plus
  `enabledAgents: {"devin-cli": true, "devin-cloud": false}` — with cloud not
  instantiable, a drifted preferredAgent can no longer open Cloud.

## Tool

`powershell -NoProfile -ExecutionPolicy Bypass -File scripts\devin_cwd_doctor.ps1 -AgentPin <sub>`

| sub | effect |
|---|---|
| `-Check` | exit 0 when pinned or a valid allow-cloud flag exists; exit 2 on DRIFT/CORRUPT |
| `-Heal` | backup into `var\devin-local-pin\settings.json.bak-<ts>` then restore the two keys only; skips when flag valid or already pinned |
| `-AllowCloud` | sets `enabledAgents.devin-cloud=true` and writes `var\devin-local-pin\allow-cloud.flag` (4 h); Heal pauses while the flag is valid |
| `-InstallSchedule` | registers task `AWX-DevinLocalPin` (logon + every 10 min -> `-AgentPin -Heal`); reports instead of overwriting an existing task |
| `-UninstallSchedule` | removes the task |

Every run appends one line to `var\devin-local-pin\pin.jsonl`.
Parse failure = no write, exit 2 (`no-write-*`). `.vscode\settings.json` keeps
the same two values as a mirror only.

## Use Cloud on purpose

`-AgentPin -AllowCloud` once, work within 4 h; `-Heal` and the scheduled task
leave the setting alone until the flag expires.

## Rollback

- Keys: restore `var\devin-local-pin\settings.json.bak-<ts>` over
  `%APPDATA%\devin\User\settings.json` (or re-enable cloud by editing the two keys).
- Watch: `-AgentPin -UninstallSchedule` then delete `var\devin-local-pin\`.

## Evidence / ledger

`data\agent-handoff\devin-devin-local-session-pin-b8204d0c\` (EVIDENCE.md with
the preferredAgent flip table vs workspace creation times, STATUS.md,
FOR_CODEX.md). Journal task `devin-local-session-pin-7ce1a37e`.

## Hard stops

- Never print `devin.acp.agentPreferences.devin-cloud.org_id` or any secret.
- Never write `state.vscdb`, `acp-messages`, `Workspaces\*` — read-only.
- No `push`/`commit`; this is local config, not a repo change-set to ship.
