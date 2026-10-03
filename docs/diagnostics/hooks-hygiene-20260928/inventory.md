# Hook inventory (demo-1/src, 2026-09-28)

## `.codex/hooks.json` (Codex Desktop/CLI project hooks)

| event | matcher | command (Windows) | timeout | statusMessage |
|---|---|---|---|---|
| UserPromptSubmit[0] | – | `powershell -NoProfile -ExecutionPolicy Bypass -File .codex\hooks\project_capabilities_hook.ps1` | 40 | Checking project resource capabilities |
| UserPromptSubmit[1] | – | `powershell -NoProfile -ExecutionPolicy Bypass -File .codex\hooks\source_edit_root_hook.ps1` | 2 | Checking source-edit preflight |
| PreToolUse[0] | `^(apply_patch|write|edit|notebook_edit)$` | `powershell ... -File scripts/devin_pre_edit_guard.ps1` | 15 | Checking source-edit lease conflicts |
| PreToolUse[1] | `^Bash$` | `powershell ... -File scripts/agent_work_guard.ps1` | 12 | Checking path/retry work guard |
| PostToolUse[0] | `^Bash$` | `powershell ... -File scripts/agent_work_guard.ps1` | 12 | Recording path/retry work guard |

POSIX `command` variants: unchanged `sh -c` walk-up scripts + `source_edit_triage.sh`
(single-quoted → no interpolation bug). Trust: `~/.codex/config.toml`
`[hooks.state.'...\.codex\hooks.json:<event>:<gi>:<hi>'] trusted_hash` — must be
re-reviewed in `/hooks` after this definition change.

## `.devin/hooks.v1.json` (Devin CLI)

| event | matcher | command | timeout |
|---|---|---|---|
| UserPromptSubmit[0] | – | `powershell ... -File .codex\hooks\project_capabilities_hook.ps1` | 40 |
| UserPromptSubmit[1] | – | `powershell ... -File .codex/hooks/source_edit_triage.ps1` | 15 |
| PreToolUse[0] | `^(write|edit|apply_patch|notebook_edit)$` | `powershell ... -File scripts/devin_pre_edit_guard.ps1` | 15 |
| PreToolUse[1] | `^exec$` | `powershell ... -File scripts/agent_work_guard.ps1` | 12 |
| PostToolUse[0] | `^exec$` | `powershell ... -File scripts/agent_work_guard.ps1` | 12 |

## `.grok/hooks/` (Grok; untrusted-folder status noted in `hook_status()`)

- `pre-edit-guard.json` → `scripts/devin_pre_edit_guard.ps1`
- `work-guard.json` → `scripts/agent_work_guard.ps1` (`run_terminal_command`)

## Implementations

- `scripts/agent_work_guard.ps1` → stdin/stdout/stderr redirect + trace at
  `var/agent-work-guard/hook-trace.jsonl` → `scripts/agent_work_guard.py hook`
- `scripts/agent_work_guard.py` — retry/path guard, schema `awx.agent-work-guard.v2`,
  agents: grok=`run_terminal_command`, codex=`Bash`, devin=`exec`
- `scripts/devin_pre_edit_guard.ps1` — source-edit lease guard; block JSON + exit 2
  only on real conflicts; silent exit 0 otherwise
- `.codex/hooks/source_edit_triage.{ps1,py,sh}` — bounded stdin (64 KiB) prompt
  classifier; emits `hookSpecificOutput` only on source-edit intent; always exit 0
- `scripts/awx_device_bus.py hook` — resource-capability `hookSpecificOutput`;
  now exit 0 unconditionally for the `hook` action
- `.codex/hooks/project_capabilities_hook.ps1` + `source_edit_root_hook.ps1` —
  new `-File` entry shims replacing interpolating `-Command` payloads

## Observed but not touched (host/user-level)

- `~/.codex/config.toml` `notify = ["…\codex-computer-use.exe","turn-ended"]` —
  Codex Desktop spawner fails `os error 206` (command line too long). Host-side,
  outside repo hooks; recommend removing the notify entry or updating Codex.
