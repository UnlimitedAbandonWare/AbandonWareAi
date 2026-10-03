# 00 Seed inventory — codex-quarantine-9only-20260919

Contract: `DEMO1-DEVIN-QUARANTINE-SEED-HARMONY-20260928`. Task: `quarantine-seed-harmony-0928-aa8a97fd` (devin).

- Seed root (read-only): `C:\AbandonWare\_rescue\codex-quarantine-9only-20260919`
  (brief's `C:\AbandonWare_rescue` spelling resolves to this `C:\AbandonWare\_rescue` path — 사실)
- Manifest: `apply-9only.jsonl` — 9 sessions, all `class=child-stale-no-evidence`, `result=moved`, pre/post sha256 equal per row.
- Scanner: `scripts/quarantine_seed_mine.py`, verified run `b85f9af5` (exit 0, 2026-09-28T12:51Z). All 9 files scanned line-streamed; no whole-file memory load (130MB file included).
- Raw outputs: `seed_mine.md` + `seed_mine.csv` (this dir), run log under `data/agent-handoff/codex-autonomy/quarantine-seed-harmony-0928-aa8a97fd/verify-t1/`.

## Sessions

| session (short id) | dir | bytes | lines | class | top mined refs |
|---|---|---|---|---|---|
| `01a08a35-d57a` | 2026/09/10 | 136,647,778 | 15,769 | child-stale-no-evidence | `agent_code_evidence_gate.py`×306, `test_agent_code_evidence_gate.py`×271, `demo1-agent-code-evidence-gate`×262, `demo1-consolidating-notebook-directives`×243, `chat_ui_stream_contract_tests.js`×189 |
| `01a093c3-8f47` | 09/12 | 16,361,283 | 847 | same | `demo1-invisible-eye`×38, `demo1-goal-asset-preservation`×33, `demo1-macsrc-smb-direct-patch`×27 |
| `01a09465-736f` | 09/12 | 2,266,471 | 163 | same | `probe_search_repeat_trace_snapshot.ps1`×10 +its tests×7 |
| `01a09467-19e1` | 09/12 | 1,259,315 | 200 | same | `devin_client.py`×31, `Invoke-Devin.ps1`×12 |
| `01a09467-becb` | 09/12 | 643,605 | 134 | same | meta/invisible-eye/goal-asset skills ×6 each, `next_step.py`×2 |
| `01a0946b-f29e` | 09/12 | 370,832 | 82 | same | `awx_mcp_completion_audit.py`×8, `awx_mcp_toolbox.py`×8 |
| `01a094ba-97ac` | 09/12 | 209,420 | 11 | same | GLM worker spawn (`Newton`, depth 1); `early_done_no_cmd` flag |
| `01a094bb-4984` | 09/12 | 2,562,439 | 54 | same | `device_probe_gpt_tool/compare.ps1`×16, `demo1-chat-design-acceptance`×10 |
| `01a094e1-0550` | 09/12 | 1,242,893 | 125 | same | `chat_ui_vibe_listener.ps1`×26, `device_probe_gpt_tool.ps1`×12, `desktop_safe_patch_harness.ps1`×10 |

## Live cross-check (root `C:\AbandonWare\demo-1\demo-1\src`)

| token | kind | mentions | sessions | live |
|---|---|---|---|---|
| `scripts/agent_code_evidence_gate.py` | script | 314 | 2 | exists |
| `.agents/skills/demo1-macsrc-smb-direct-patch` | skill | 282 | 9 | exists |
| `scripts/source_health_scorecard.py` | script | 178 | 4 | exists |
| `scripts/source_health_validation_loop.py` | script | 164 | 1 | exists |
| `.agents/skills/demo1-invisible-eye` | skill | 133 | 9 | exists |
| `.agents/skills/INDEX` | skill | 89 | 9 | renamed → `INDEX.md` |
| `scripts/awx_mcp_completion_audit.py` | script | 32 | 4 | exists |
| `scripts/chat_ui_vibe_listener.ps1` | script | 26 | 1 | exists |
| `scripts/device_probe_gpt_tool.ps1` | script | 28 | 2 | exists |
| `scripts/next_step.py` | script | 16 | 8 | **missing** |
| `scripts/devin_client.py` | script | 31 | 1 | **missing** |
| `scripts/invoke_consolidated_program.ps1` | script | 56 | 1 | **missing** |
| `scripts/new_docker_autograder_job.ps1` | script | 34 | 1 | **missing** |
| `scripts/invoke_docker_autograder.ps1` | script | 33 | 1 | **missing** |

(Full table: `seed_mine.csv`, top-60 in `seed_mine.md`.)

## Reads

- `next_step.py` mined in 8/9 sessions but absent live — highest-frequency dead reference; candidates: renamed helper or removed-era script. Any doc/skill still pointing at it is a stale-seam signal (`missing_script_path` tags).
- `demo1-chat-design-acceptance` skill referenced in `01a094bb` — check `seed_mine.csv` for live status before reusing.
- Grok's pre-listed seeds (`chat_ui_vibe_listener.ps1`, `device_probe_gpt_*.ps1`, `smoke_chat_debug_*.ps1`, `awx_mcp_*.py`, `agent_code_evidence_gate.py`, `source_health_scorecard.py`, `desktop_safe_patch_harness.ps1`, `next_step.py`, `Invoke-Devin.ps1`) all confirmed present in the scan — 재검증 done, not pasted.
