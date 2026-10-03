# 02 Rollout failure tags — per-session keyword tags

Source: `quarantine_seed_mine.py` run `b85f9af5` (stream-full scan). Tag = keyword-class hit count, not proof of failure; counts are mention-level (사실 as observed signals, 추정 as failure causes).

| session | lines | tagCounts (nonzero) | flags |
|---|---|---|---|
| `01a08a35` (130MB) | 15,769 | lease_collision 695 · secret_risk 641 · missing_script_path 333/:err 222 · wrong_launcher 263/:err 176 · status_cas 40 · full_suite_burn:gradle 46 | heavy multi-failure session; toolCallLines 1426 vs doneClaimLines 4648 (done words ≪? — claims include instructions text; not all are self-claims) |
| `01a093c3` | 847 | lease_collision 111 · secret_risk 47 · status_cas 3 · missing_script_path 23/:err 12 · wrong_launcher 8/:err 8 · full_suite:gradle 5 | lease+CAS chatter dominant |
| `01a09465` | 163 | missing_script_path 17/:err 7 · secret_risk 11 · lease_collision 4 · wrong_launcher 3/:err 3 · full_suite:gradle 1 | path-miss flavor |
| `01a09467-19e1` | 200 | missing_script_path 17/:err 12 · secret_risk 22 · lease_collision 11 · wrong_launcher 2/:err 2 · full_suite:gradle 1 | devin_client/Invoke-Devin refs — missing live |
| `01a09467-becb` | 134 | lease_collision 10 · secret_risk 4 · full_suite_burn 2(+gradle1) · missing_script_path 3/:err 3 · wrong_launcher 2/:err 2 | full-suite text hits |
| `01a0946b` | 82 | secret_risk 18 · missing_script_path 7/:err 5 · lease_collision 5 · wrong_launcher 2/:err 2 · full_suite:gradle 1 | awx_mcp seam |
| `01a094ba` | 11 | early_done_no_cmd (doneClaimLines 5, toolCallLines 0) + lease 3, secret 3, msp 3/:err 3, wl 2/:err 2, gradle 1 | GLM worker spawn; no tool calls — pure-text child |
| `01a094bb` | 54 | lease_collision 7 · secret_risk 7 · missing_script_path 5/:err 4 · wrong_launcher 3/:err 3 · full_suite 1(+gradle1) | device_probe seam; `demo1-chat-design-acceptance` skill ref |
| `01a094e1` | 125 | missing_script_path 31/:err 18 · wrong_launcher 14/:err 9 · lease_collision 13 · secret_risk 12 · full_suite:gradle 4 | chat_ui_vibe_listener seam |

## Tag legend

- `early_done_no_cmd` — done-claim lines>0 with zero tool-call lines (strongest no-evidence signal)
- `lease_collision` — `lease|ownerId|source_edit_session` mentions
- `status_cas` — `expect-sha256|status_doc.py|changed-since-read|CAS`
- `secret_risk` — `api[_-]?key|PRIVATE KEY|password|secret` mentions (risk signal, not exposure proof)
- `full_suite_burn(:gradle)` — full-suite text / `gradlew…test` without `--tests`
- `wrong_launcher(:err)` — `.bat` mentions / `.bat`+error same line
- `missing_script_path(:err)` — `scripts/` mentions / `scripts/`+error same line

Caveat (추정 boundary): mention counts include instruction/context text (e.g. base_instructions, AGENTS excerpts), so nonzero ≠ actual failure event; error-adjacent (`:err`) and `early_done_no_cmd` are the stronger signals.
