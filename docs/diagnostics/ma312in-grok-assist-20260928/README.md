# ma312in Grok assist — 2026-09-28

Assist only. Codex owns product patches (Track NW NW1–NW5, then Track API P0/P1).
This folder does not change `main/java`, `chat.js`, YAML, or tests.

| Item | State |
|---|---|
| A1 plugin loadout | written |
| A2 NW skeleton | written |
| VERIFY_NW | command table written; exit column is Codex |
| A3 API contract map | written from live reads; no code edit |
| A4 rules merge proposal | proposal only; `AGENTS.md` not edited |
| CODEX_HANDOFF | one blocked seam |
| A6 git/lease note | written because a live lease overlaps NW3 config |
| New skill under `.agents/skills` | not created |
| Product source diff | none |
| Gradle / focused tests | NOT_RUN |
| Browser HOLD / admin harden | NOT_RUN |
| `web.search` live ON | NOT_RUN |
| commit / push | not done (paste forbids unapproved commit) |

Task journal: `ma312in-grok-assist-20260928-b891f36c`.

## Codex inputs (Downloads, not copied into the tree)

| Role | Path |
|---|---|
| GOAL merge | `C:\Users\nninn\Downloads\GOAL_MERGE_ma312in_no_wait.txt` |
| NW directive | `C:\Users\nninn\Downloads\ma312in_no_wait_fault_tolerance_directive_2026-09-28.md` |
| NW evidence | `C:\Users\nninn\Downloads\ma312in_evidence_2026-09-28.md` |
| API paste | `C:\Users\nninn\Downloads\PASTE_CODEX_ma312in_api_orch.txt` |
| API directive | `C:\Users\nninn\Downloads\DIRECTIVE_CODEX_ma312in_api_orch.md` |
| API SSOT | `C:\Users\nninn\Downloads\ma312in_CODEX_API_ORCHESTRATION_2026-09-28.md` |
| API evidence | `C:\Users\nninn\Downloads\ma312in_source_evidence_2026-09-28.json` |
| API zip | `C:\Users\nninn\Downloads\ma312in_Codex_API_Orchestration_2026-09-28.zip` |
| Continue card | `C:\Users\nninn\Downloads\PASTE_CODEX_ma312in_kickoff_CONTINUE.txt` |
| Grok paste | `C:\Users\nninn\Downloads\PASTE_GROK_ma312in_assist.txt` |

No separate `*no_wait*pack*.zip` was in `Downloads` at listing time. The NW directive plus `ma312in_evidence_2026-09-28.md` are the pack surface. `ma312in.zip` stays a snapshot; do not copy it over the live tree. ZIP sha in the API directive was not re-hashed here.

## Codex next action

Skip the leased budget/routing files named in `CODEX_HANDOFF.md`. Start NW1 on `main/resources/static/js/chat.js` and the existing client-deadline assertion in `scripts/chat_ui_stream_contract_tests.js` (around lines 5850–5857), which currently requires `streamClientDeadlineMs` to return null.
