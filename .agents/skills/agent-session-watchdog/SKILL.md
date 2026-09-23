---
name: agent-session-watchdog
description: Shared Devin/Grok/Codex/Cline session-health watchdog — scan agent session stores for verified failure patterns and run bounded diagnostics
---

# agent-session-watchdog

Shared session-health watchdog for all four agents. Read-only: it never
modifies, moves, or deletes session files. Built from real failure evidence
(`docs/CODEX_SESSION_SOURCE_REGRESSION_AUDIT_20260919.md` + quarantined
sessions under `C:\AbandonWare\_rescue\codex-quarantine-9only-20260919`).

## Entry

```powershell
python -B scripts/agent_session_watch.py stores        # discover 4-agent session stores
python -B scripts/agent_session_watch.py patterns      # pattern table
python -B scripts/agent_session_watch.py scan          # bounded scan (default: codex, last 72h)
python -B scripts/agent_session_watch.py watch         # scan + auto-diagnostics on 'auto' findings
python -B scripts/agent_session_watch.py diagnose      # bounded diagnostic bundle only
Watch-Agents.bat                                       # BAT wrapper (same args)
```

Useful flags: `--agent codex|grok|devin|cline|all`, `--file F` / `--dir D`
(point at the quarantine dir or any rollout), `--since-hours`, `--stale-hours`,
`--max-files`, `--max-file-mb`, `--out <report-dir>`.

## Detected patterns (verified against real sessions)

| ID | Pattern | Severity | Signature |
|---|---|---|---|
| P1 | apply-patch-context-miss | auto | `Failed to find expected lines in <f>` ≥2 on same file — stale preimage editing |
| P2 | apply-patch-malformed | warn | `invalid patch` / other verification failure |
| P3 | repeated-failed-command | auto | same normalized exec_command failed ≥3 |
| P4 | error-streak | warn | ≥4 consecutive failed tool calls |
| P5 | context-compaction-heavy | warn | compacted ≥10 / file ≥50MB / tasks ≥50 |
| P6 | model-switch-mid-session | warn | non-developer model_switch, or >=2 switches. A single developer-role marker is the Codex session template (quarantine 9/9 false positives) |
| P7 | goal-conflict | warn | create_goal unfinished-goal rejection |
| P8 | edit-outside-cwd | warn | patch target outside the session cwd root |
| P9 | stale-incomplete-session | warn | task_started>task_complete and idle past --stale-hours |
| P10 | subagent-fanout | info | spawn_agent+followup_task >=20 |
| P11 | in-output-command-failure | auto | Script completed but output has Cannot-find-path / CreateProcess-Rejected (>=3 auto, else warn) |
| P12 | same-target-retry | auto | same relative file in >=3 failed exec_command calls even when the one-liner text changed |
| P13 | scan-coverage-gap | warn | home discovery still scans oversized rollout-*.jsonl (max_lines bound) and reports they exceeded --max-file-mb. Hook never re-reads those files. |
| G1 | generic-error-markers | warn | non-codex jsonl: >=3 error markers |

`auto` severity = safe to auto-run the diagnostic bundle; `warn`/`info` =
report only, never auto-remediate. The bundle is read-only:
`agent_preflight.py`, `work_journal.py list --active`,
`source_edit_session.ps1 -Action status -Json` (+ `debug_rag_stack.ps1 status`
with `--with-server`).

## Session stores

- **Codex**: `~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<id>.jsonl`
  (`session_meta`/`response_item`/`event_msg`/`compacted` records;
  tool calls = `custom_tool_call`/`function_call`, exec via
  `tools.exec_command`, patches via `tools.apply_patch`,
  failures marked `Script failed`/`Script error` **or** in-output
  `Cannot find path` / `CreateProcess Rejected` even when the envelope says
  completed). `--dir`/`home` scan only `rollout-*.jsonl` (skip `apply-*.jsonl`
  sidecars). Parent/child edges live in `state_*.sqlite` — use
  `scripts/awx_session_evidence.py parents` for chains.
- **Grok**: `~/.grok/sessions/<url-encoded-cwd>/<id>/{chat_history,events}.jsonl`
  + `session_search.sqlite` + `active_sessions.json`.
- **Devin**: `%APPDATA%/devin` — `cli/transcripts`, `summaries/*.md`,
  `sessions.db`.
- **Cline**: `~/Documents/Cline` — rules-only here; no local session store
  observed (store discovery reports `not_observed`-style notes).

## Rules

- Exit codes: 0 clean, 3 warnings only, 4 auto-findings, 2 usage, 1 error.
- Never print raw prompts/messages — counts, paths, ≤140-char normalized
  command keys only.
- Filename timestamp = session creation; file mtime = last activity. A resumed
  old session is "recent" by mtime — that is intended.
- This tool detects and diagnoses; repair still goes through the work-ledger /
  lease / three-way-preflight gates. Detection is not mutation authority.
- Complements `scripts/devin_pre_edit_guard.ps1` (pre-write block hook): the
  hook prevents conflicting edits; this watchdog finds in-flight/session-level
  failure loops after the fact.

## Prevention (in-session brake)

Shared verdict in `scripts/agent_work_guard.py`. Per-agent hook I/O is separate
(Grok `deny` + `toolName`; Codex `Bash` + `permissionDecision=deny`; Devin
`exec` + `decision=block`). Official matchers: Codex `^Bash$`, Devin `^exec$`,
Grok `run_terminal_command|Bash`.

```powershell
python -B scripts/agent_work_guard.py status --root .   # files/matchers/trust; not live fire
python -B scripts/agent_work_guard.py check --root . --cmd "Get-Content main/java/Foo.java"
```

Allowed: existing `src/test` reads, New-Item/Set-Content create, rg/Test-Path
search, outside reads (quarantine). Blocked: missing-file **reads**, `src/main`
join onto an already-`src` root, same agent+actor+path+cause failing >=3 times
on PostToolUse. Creating the file or a successful read resets a missing streak.
Historical P12 advise does **not** write the live ledger. File presence / trust
is not runtime proof. Grok project hooks load at session start — a new
`.grok/hooks/*.json` added mid-session is not called until a new session.
Trace: `var/agent-work-guard/hook-trace.jsonl` (tool name/id, decision, exit,
elapsed; no cmd/body). No enter line means not-called. Grok fail-open on
timeout; Codex skips untrusted hook hashes.
