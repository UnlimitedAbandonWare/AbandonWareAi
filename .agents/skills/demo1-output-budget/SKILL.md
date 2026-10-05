---
name: demo1-output-budget
description: SSOT for tool-output token budget on demo-1 — bounded reads (rg/line-range/out_peek), JSON-via-file, wait discipline, and patch retry rules that keep Codex sessions from burning millions of tokens on dumped files
---

# demo1 Output Budget (SSOT)

Session evidence (2026-10-01~03, `data/agent-handoff/devin-skill-friction-8eeb784c/baseline.json`):
truncated tool outputs ~585 events, median 6.2k max 742k original tokens;
`wait` 440 calls of short polling; `cannot create a new goal` 9;
`refused source-lease-drift` and `refused as a conflict` loops.
These are tool-habit failures — the rules below exist so the tool, not
willpower, bounds the output.

## 1. Reading files and logs

- Never `Get-Content <file>` / `print(<whole json>)` on an unbounded source.
  First reach is a targeted read:
  `rg -n "<pattern>" <file>` or a line range
  (`Get-Content f | Select-Object -Skip N -First M`).
- When a bounded view is not obvious, use the peeker — it hard-caps output
  and reports what it hid:
  `python -B scripts/out_peek.py <file> [--grep PAT] [--head N] [--tail N]
  [--json-keys a.b,c] [--max-chars 6000]`
- exec commands that can explode (build logs, session JSONL, `git log -p`,
  directory dumps): pass `max_output_tokens` (e.g. 4000) on the call, or
  redirect to a file and `out_peek.py` the file.
- `Warning: truncated output` means the command already lost information —
  re-run narrower; do not parse the truncated tail as if complete.

## 2. Commands that must emit JSON

- Route JSON-producing commands through the file, not the screen:
  `python -B scripts/json_safe_run.py --json-out <f>.json --require-json -- <cmd>`
  → one summary line on screen; parse `<f>.json` after.
- Code-mode rule: `JSON.parse` only when `exit_code==0` AND the first
  non-space byte is `{` or `[`. `Traceback`, `Warning: truncated`, or any
  banner means DO NOT parse — re-run via `json_safe_run.py`.
- Many repo scripts already support `--json-out <file>` — prefer writing
  JSON to a file over capturing it in the tool result (list:
  `docs/codex/json-out-scripts.md`).

## 3. Waiting on long work

- One wait, adequate timeout — not a poll loop. Set the exec/wait timeout
  to cover the expected duration once.
- Prefer completion evidence over re-polling: a done-flag file
  (`<task>/done.flag`, checkpoint `checkpoint.json` `status`) or one
  `out_peek.py --tail` of the log.
- Three consecutive waits on the same target with no change → stop
  waiting, inspect the process/log once, then decide.

## 4. Patches that failed once

- `Failed to find expected lines` / `invalid hunk` → re-read the target
  line range NOW (`out_peek.py` or rg), rebuild context, apply ONCE.
  Never retry the identical patch.
- Preflight a patch file first when unsure:
  `python -B scripts/patch_preflight.py <patch> --root .`
  → reports stale-context hunks with the exact line range to re-read,
  doubled roots (`src\src\...`), missing targets, bad `@@` headers.
- `CreateProcess` refusals on `codex-runtimes` paths: quote the whole
  path — the runtime dir sits under `C:\Users\...` with spaces upstream.

## 5. Refusal loops (lease/doc conflicts)

- `refused source-lease-drift`: lease.json changed mid-cycle (heartbeat
  renewals). `python -B scripts/checkpoint_doctor.py --run <cycle>` shows
  lease seconds-to-expiry and the next command — seal/finish before expiry.
- `refused as a conflict` (status_doc expect-sha): re-`read` the row,
  re-`update-row` with the fresh `--expect-sha256`. Never re-send the
  same sha.
- `refused secret-pattern`: the message names file:line — mask or
  env-ize the literal; do not fight the guard.

## 6. Same failure three times — stop (3-Strike)

- Same error signature on the same target (patch `Failed to find expected
  lines`, wait-no-change, lease/guard refusal, non-JSON banner) three
  consecutive times → stop the loop. Never a 4th identical attempt.
- The one-retry allowances above (§3: stop after 3 unchanged waits, §4:
  re-read then apply ONCE) are the strikes themselves, not extra chances.
- On stop: record the cause in one line in `state.md` or a journal note,
  then hand off or ASK the user — do not keep burning context on the loop.

## Related tools

`out_peek.py` · `json_safe_run.py` · `patch_preflight.py` ·
`checkpoint_doctor.py` · `spawn_preflight.py` ·
`codex_session_friction.py` (periodic re-measure vs baseline.json).
