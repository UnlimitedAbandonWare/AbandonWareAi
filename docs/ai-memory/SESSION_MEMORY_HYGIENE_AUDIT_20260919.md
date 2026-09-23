# Session/memory hygiene audit — 2026-09-19

Task: `session-memory-hygiene-83f48854`
Evidence date: 2026-09-19
Rule: current source plus live verification outrank memory; old Git history is not backup.

## Verified inventory

- Codex home sessions: 2,723 rollout files; `archived_sessions` 13 preserved.
- Codex memory: `memories/` reset to operational notice; raw and summary files empty.
- Residual Codex memory pipeline: `stage1_outputs` 76 rows, `jobs` 405 rows.
- Codex memory switches: `generate_memories=false`, `use_memories=false`.
- Cline local sessions: 17 session dirs — 4 failed, 12 idle, 1 running current task.
- Grok: 2 active project sessions; global memory still template; workspace memory exists.
- Claude: 3 local session files plus 14 project files; small.
- Repo journals: 35 total; 33 closed verified, 2 partial/hold retained; 1 current in_progress.

## Preservation decisions

- Preserve all active sessions, including Cline `session_1789812434265_wqtzm`.
- Preserve all 13 Codex archived sessions.
- Preserve 2 partial/hold repo journals as recovery evidence.
- Preserve API keys, auth state, secrets, and environment configuration untouched.
- Preserve verified recent fixes and their checkpoint preimages.

## Cleanup proposal

### Eligible after manifest backup: Codex 9 new candidates, 154.1 MiB

These 9 meet the conservative `child-stale-no-evidence` rule in the rerun:

- `01a08a35-d57a-7810-bf33-c257776a745b`
- `01a093c3-8f47-7de1-9e62-95709128b406`
- `01a09465-736f-7de3-92bb-fe2f12b92bdf`
- `01a09467-19e1-7210-bd3e-b83e165f1dd4`
- `01a09467-becb-7841-b64e-e4c036cc11e6`
- `01a0946b-f29e-7b62-87b2-9512b37811df`
- `01a094ba-97ac-7e63-aa29-3bff39098600`
- `01a094bb-4984-7610-874b-e6b3715bc788`
- `01a094e1-0550-7e23-983f-5dbd8ee4adc2`

Required order:

1. Back up the current `manifest.jsonl` before any move.
2. Use `scripts/codex_home_quarantine.py apply` only for the new 9-file manifest.
3. Keep them quarantined; permanent deletion needs a separate explicit approval.
4. Do not treat the old `apply-log.jsonl` as restorable payload: its prior
   quarantine payload was already permanently purged.

### Proposal only, no direct deletion here

- Cline 4 failed sessions from 2026-09-19 morning.
- Cline trivial/no-op idle sessions such as greeting-only prompts.
- Reason: no verified Cline backup/restore path or in-app delete API was established.

### Not cleanup targets

- Grok global memory template and active project sessions.
- Small Claude history.
- Small Cline cache and logs.
- Old stage1 memory rows through direct SQLite edits; that path is prohibited.
- Old reports and handoffs inside the repo; they are reference-only, not live memory.

## Common memory created

- `docs/ai-memory/Dynamic_RAG_Orchestration_Platform.memory.md`
- `docs/ai-memory/AGENT_MEMORY_ROUTING.md`

These match the canonical path already expected by
`tools/context_purity_score.ps1` and related tests.
