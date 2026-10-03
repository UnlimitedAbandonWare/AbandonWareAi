# 03 Agent harmony kit — when to use what

Contract: `DEMO1-DEVIN-QUARANTINE-SEED-HARMONY-20260928`. Task: `quarantine-seed-harmony-0928-aa8a97fd` (devin).
Audience: Codex / Devin / Clean(Cline) / Grok sessions in this root. All tools are stdlib-local, read-only on foreign state, and compose with the existing lease/journal/checkpoint rails — nothing here replaces `AGENTS.md` gates.

## New rails (this kit)

| Tool | Use it when | Call |
|---|---|---|
| T1 `scripts/quarantine_seed_mine.py` | You need to know what past quarantined sessions referenced (scripts/skills) and whether those refs still exist live | `python -B scripts/quarantine_seed_mine.py --seed <root> --root . --out-md <out.md> --out-csv <out.csv>` |
| T2 `scripts/agent_harmony_status_cas.py` | Writing a row to `PROJECT_STATUS.md`/shared md while another agent may write too (W1) | `check` → current sha/rows; `append`/`update` → fresh-read→CAS retry ≤`--tries`, emits `rebaseGuide` on conflict. Wraps `status_doc.py`; still refuses to overwrite foreign deltas |
| T3 `scripts/agent_done_evidence_guard.py` | Before claiming **Done/PARTIAL** — yours or reviewing another agent's claim (W2/W6) | `--task <taskId> [--text "<claim>"]` → exit 2 with reasons: `no-verify-event`, `verify-ref-missing-run`, `runtime-not-observed`, `claim-text:*` (delegates to `max_push_done_guard.py`), `done-claim-no-evidence-token` |

Self-check: `python -B scripts/test_agent_done_evidence_guard.py` (7/7 expected; synthetic temp journals only).

## Existing rails (already live — reuse, don't reinvent)

- `scripts/agent_preflight.py --root . --agent <name>` — entry: bus inbox, peer journals, goal-switch summary, lease guidance.
- `scripts/demo1_goal_switch_barrier.py check|switch` — objective rotation hygiene.
- `__patch_drop__/source_edit_session.ps1 begin` — target-scoped lease before scripts/source edits.
- `scripts/work_journal.py open|note|close` + `scripts/codex_work_checkpoint.py begin|seal|finish` — scope + preimage + verify record.
- `scripts/status_doc.py read|update-row|append-row --expect-sha256` — the CAS primitive T2 wraps.
- `scripts/agent_code_evidence_gate.py`, `scripts/max_push_done_guard.py`, `scripts/demo1_goal_switch_barrier.py reject-complete` — claim-side gates T3 composes.
- `scripts/agent_session_watch.py` — live session health scan (W6 sibling).

## Playbook sketch

1. **Entry**: preflight → goal-switch check → journal open with plannedScope. If `switchRequired`, switch first.
2. **Writing shared status**: T2 `check` → draft row → T2 `append`/`update`. On `conflict`, follow `rebaseGuide`; a persistent conflict means an active foreign writer — stop and journal `미기록 외부 변경` context.
3. **Claiming done**: T3 `--task <id> --text "<claim>"`. Exit 2 = the claim lacks journal verify/run.json/runtime evidence — fix the claim or gather evidence, never restate.
4. **Mining old rollouts**: T1 on a quarantine root; check `missing`/`renamed` rows before citing a script name — `next_step.py`, `devin_client.py`, `invoke_*` were mined in 8/9 sessions but are absent live.
5. **Intent matches**: an orchestrator/router playbook match that came from exclusion wording (W5) is not a mandate — re-check whether the matched seam is actually in scope before spending.

## Known gaps / policy notes

- `codex_work_checkpoint.py` secret scan flags a type annotation on a parameter literally named `token` (FP class — `token`+colon+type reads as a credential assignment). Scanner fix lane is held by foreign lease `checkpoint-scanner-jackson-read-0928` — recorded as policy-conflict; workaround used here: drop the inert annotation.
- W3 fixture-drift probe and W4 offload success-check are **proposals only** (`01_CODEX_WEAKNESS_MAP.md`), not built in this task.
- UAW spine matrix: see sibling `docs/diagnostics/uaw-harmony-foundation-0928/` + `.agents/skills/demo1-uaw-harmony-foundation/` (appendix reference only per contract §4).
