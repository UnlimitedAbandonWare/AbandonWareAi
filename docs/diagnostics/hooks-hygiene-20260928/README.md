# DEMO1-HOOKS-HYGIENE-20260928-R1

Fix for the Codex/Desktop hook fail-storm: **377 executions / 0 blocked / 377 failed**.
Scope: hooks and guards only. No ATT/GraphRAG/ChatWorkflow/RAG/product code touched.
Journal: `hooks-hygiene-0928-5694d7a7` (devin). Lease: `hooks-hygiene-0928.lock`.
Checkpoint: `data/agent-handoff/codex-autonomy/hooks-hygiene-0928-5694d7a7/cycle-1` (pre-edit preimage) + `cycle-2` (sealed post-edit).

## Contract (verified against Codex hooks documentation)

- Command hooks receive one JSON object on stdin; `commandWindows` runs on Windows.
- **Exit 0 + empty stdout = success.** Plain stdout is ignored on PreToolUse.
- PreToolUse deny: `{"decision":"block","reason":...}` or
  `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny",...}}`, or exit 2 + stderr reason.
- `decision:"allow"` / `decision:"approve"` on Codex PreToolUse/PostToolUse is
  *parsed but unsupported → the hook run is marked failed* even at exit 0.
- UserPromptSubmit supports `hookSpecificOutput.additionalContext`; exit 2 **blocks
  the user's prompt** — it must never carry an environmental failure.
- Diagnostic text belongs on stderr or trace files, never stdout.

## Root causes (classified from the screenshot categories)

### B — "Checking path/retry work guard" → invalid pre-tool-use JSON (377-fail majority)

`scripts/agent_work_guard.py` `emit_payload()` emitted `{"decision":"allow","reason":"","verdict":{...}}`
for every allowed Codex tool call, and `{"decision":"allow","recorded":...,"result":...}` for
PostToolUse. `decision:"allow"` is an unsupported field → each allowed call was counted
as a failed hook run, which matches **0 blocks / 377 fails** exactly.

### A/A2 — capabilities + source-edit preflight → exit 1

Both `commandWindows` entries were `powershell -Command "$d=...; for($i=...)"` payloads.
The spawning shell re-interpolates `$var` inside the `-Command` string before the inner
`powershell` parses it, so every `$d`/`$i`/`$p`/`$roots` token was eaten and mangled text
(`=(Get-Location).ProviderPath; for(=0;...`) was executed → parse/execution error →
exit 1 (reproduced: `hook_run_probe.py` via `cmd /c` shows the mangled script echoed,
`pwsh -Command` shows `=` unrecognized). The same bug existed in `.devin/hooks.v1.json`'s
capabilities hook.

### C — PostToolUse + secondary

- `agent_work_guard.py` PostToolUse emitted the same unsupported `decision:"allow"` shape.
- `awx_device_bus.py hook` could exit 2 on `evidence_needed` (blocks the prompt) and
  leaked exit 1/tracebacks for uncaught exception types.
- `agent_work_guard.py` `main()` printed `{"decision":"allow",...}` + exit 1 on
  unreadable stdin JSON — an exit-1 storm on transport noise.
- Unfixed/host-side: `~/.codex/config.toml` `notify` → `codex-computer-use.exe` fails
  `os error 206` (argv too long on turn notification). User-level Codex Desktop item;
  not part of the repo fail-storm.

## Fixes applied

| File | Change |
|---|---|
| `scripts/agent_work_guard.py` | `emit_payload` returns `None` for Codex allow → empty stdout; block keeps documented `decision:block` + `permissionDecision:deny`; exception path fails open (exit 0, per-agent allow); unreadable stdin → exit 0 + stderr note |
| `scripts/awx_device_bus.py` | `hook` action always exits 0 (status still reported inside `additionalContext`); `except` widened to `Exception` so nothing escapes as exit 1 |
| `.codex/hooks/project_capabilities_hook.ps1` | **new** — `-File` entry, root = `$PSScriptRoot\..\..`, soft-fail exit 0 |
| `.codex/hooks/source_edit_root_hook.ps1` | **new** — `-File` entry resolving root from own location + marker check, then `& source_edit_triage.ps1`; soft-fail exit 0 |
| `.codex/hooks.json` | two `commandWindows` `-Command` payloads → `-File` wrappers (POSIX `command` entries unchanged — single-quoted `sh` is not affected) |
| `.devin/hooks.v1.json` | capabilities `command` → same `-File` wrapper |
| `scripts/test_agent_work_guard.py` | adapter tests updated: codex allow/post → `None` payload; new fail-open test |
| `scripts/test_hooks_contract.py` | **new** — runs every configured command against synthetic stdin, asserts exit/stdout contract per host |

## Before / after (cmd /c spawn, redacted)

| hook | before | after |
|---|---|---|
| capabilities (UserPromptSubmit) | exit 1, stdout = mangled script text | exit 0, stdout = 1 JSON `hookSpecificOutput/UserPromptSubmit` (445 B) |
| source-edit preflight | exit 1, mangled text | exit 0, empty stdout (non-edit prompt) |
| work guard PreToolUse allow | exit 0, `{"decision":"allow",...}` → invalid | exit 0, empty stdout |
| work guard PreToolUse block | exit 2 + deny JSON | unchanged: exit 2 + `decision:block` + `permissionDecision:deny` + stderr reason |
| work guard PostToolUse | exit 0, `{"decision":"allow",...,"recorded":...}` → invalid | exit 0, empty stdout |
| work guard malformed stdin | exit 1 + `{"decision":"allow","hook-json-unreadable"}` | exit 0, empty stdout, stderr note |

## Verification

- `python -B -m unittest scripts.test_agent_work_guard` → 19 tests, exit 0
- `python -B -m unittest scripts.test_hooks_contract` → 10 tests, exit 0
  (drives the real `-File` commands incl. capabilities, triage, allow/block/post, malformed stdin, devin approve/block, and a regression rule forbidding `$` in `commandWindows` `-Command` payloads)
- Live Codex UI statistics: **NOT_RUN** — stats reset only in a fresh Codex session.

## Recurrence prevention

- `test_hooks_contract.py::CommandShape` fails if any `commandWindows`/`-Command`
  payload contains `$` (the interpolation-eaten bug class).
- Contract tests pin empty-stdout-on-allow for Codex and `approve|block` for Devin.
- Host-side caveat: `.codex/hooks.json` edits change the hook-definition hash →
  Codex `/hooks` marks them for re-review. That is expected once, not a failure.
  `~/.codex/config.toml` `[hooks.state.*]` `trusted_hash` records are keyed to the
  old definitions; the user must re-trust in the Codex UI.
