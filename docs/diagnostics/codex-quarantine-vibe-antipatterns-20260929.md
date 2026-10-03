# Codex quarantine → vibe AUTO anti-pattern catalog

Contract `DEMO1-CLEAN-QUARANTINE-VIBE-AUTO-OPT-20260929` §B.
Generated 2026-09-29 by `scripts/quarantine_codex_rollout_mine.py` (streaming;
no whole-file loads, no raw message text emitted).

Corpus: `C:\AbandonWare\_rescue\codex-quarantine-9only-20260919` +
`codex-quarantine-20260919|20260926|20260927` + `codex-context-cleanup-20260919`
(read-only; originals untouched, sha-preserved moves only).

Result: **133 session files, 778,287,254 bytes** mined.
Machine report: `data/agent-handoff/clean-quarantine-opt-20260929/mine-report.json`
Markdown mirror: same dir `ANTIPATTERNS.md`.

## Observed stop-reason totals

| stop reason | sessions | signal basis |
|---|---|---|
| child-stale-no-evidence | 38 | `parent_thread_id` + tasks incomplete + zero test/gate/handoff evidence |
| token-blowup | 35 | bytes>=50MB or turn total_tokens>=200k or compacted>=2 |
| done-no-evidence | 16 | assistant Done/verified claims, no test/gate/handoff markers on signal lines |
| plugin-sprawl | 14 | `recommended_plugins` / `@openai-curated-remote` blocks (>=3 lines) |
| auth-claim-no-proof | 7 | assistant admin/login/auth verified-claims with no gate evidence |
| quiz-spam | 3 | assistant approval-quiz lines >=8 or >10/1k lines (max 18/1k seen) |
| goal-read-as-done | 0 | `evidence_needed` — rule wired, no corpus hit this pass |
| gate-loop | 0 | `evidence_needed` — gate churn without exit evidence not observed |
| dup-service-suspect | 0 | `evidence_needed` — tightened regex (bare `JobService` mention excluded) |

Evidence letters in session rows: T=test-cmd line, G=gate/exit line,
H=handoff/journal line — counted on assistant + tool-call lines only
(dev/user template boilerplate excluded after first-pass false-positive fix).

## Buckets → rails

| # | bucket (contract) | corpus evidence | rail (existing → extended) | gap |
|---|---|---|---|---|
| 1 | Done without evidence / goal-read-as-done | 16 sessions `done-no-evidence` (exec calls ran, zero test/gate/handoff); `goal-read-as-done` rule wired, 0 hits → `evidence_needed` | `scripts/agent_done_evidence_guard.py` (exists, journal+text gate) + `max_push_done_guard` + `demo1-goal-switch-barrier reject-complete` | none — guard already covers; extend only if corpus later shows goal-md pattern |
| 2 | Approval quiz for reversible local work | 3 sessions `quiz-spam` (18/1k peak, 136MB session 3/1k) | NEW `scripts/agent_vibe_auto_decision.py` (CLI mirror of `.agents/skills/demo1-vibe-selfask-judge-auto`) | filled this pass |
| 3 | Stale child, empty title, no handoff | 38 sessions `child-stale-no-evidence`; 9only manifest: 9× `child-stale-no-evidence`, `title=` empty, sha preserved | `scripts/agent_session_watch.py` P14 (NEW pattern: info while fresh, warn past `--stale-hours`) + `agent-session-watchdog` | filled this pass |
| 4 | PROTO_OPEN browser/admin smoke ≠ auth proof | 7 sessions `auth-claim-no-proof` | `agent_done_evidence_guard.py` `runtime-not-observed` + `docs/PROTOTYPE_AUTH.md` | none |
| 5 | Plugin sprawl / unrelated connectors | 14 sessions carry recommended-plugin catalogs (context weight, not actions) | `demo1-codex-plugin-roles` skill — unknown plugins stay off | none; count is a bloat metric, not a defect verdict |
| 6 | Duplicate services vs existing JobService/scripts | 0 hits after regex tightening → `evidence_needed` (bare class-name mention ≠ duplication) | `awx-source-surgeon` + "prefer existing seams" rails | none now; revisit if a future corpus shows `new service|reimplement` hits |
| 7 | Token blowup on full-session paste / runaway context | 35 sessions (incl. 136.6MB / 15,769-line rollout, compacted>=2) | NEW `scripts/quarantine_codex_rollout_mine.py` (streaming, `--max-bytes-per-file`, `--sample-lines`) + `quarantine_seed_mine.py` + `demo1-agent-api-spend-guard` | filled this pass |

## Cross-cutting observations (facts, not verdicts)

- `exec` dominates tool calls (4,210); `send_message` 386, `spawn_agent` 4 —
  the quarantined children mostly *talked*, few *spawned*.
- The 136MB session alone carries 109 done-claim lines with T/G/H evidence
  present — yet was quarantined `child-stale-no-evidence`: done-claim volume
  is not the same as verifiable completion, and the quarantine class keys on
  child+stale, not evidence absence alone. Miner flags reflect its own rules,
  not the mover's exact classifier.
- Env-name checks like `Test-Path Env:AI_GATEWAY_API_KEY` appear in command
  heads — names only; no secret values were emitted into reports by design.
- One `manifest` pseudo-row appears in the session table (a small manifest
  jsonl inside a sibling quarantine); it produced no flags and is kept as an
  honest inventory row.

## New rail inventory (this pass)

| artifact | kind | verify |
|---|---|---|
| `scripts/quarantine_codex_rollout_mine.py` | streaming miner | `scripts/tests/test_quarantine_codex_rollout_mine.py` 8/8 |
| `scripts/agent_vibe_auto_decision.py` | Self-Ask judge CLI (AUTO/ASK_ONCE/HOLD; exits 0/3/4) | `scripts/test_agent_vibe_auto_decision.py` 11/11 |
| `agent_session_watch.py` P14 | early child-stale flag | `test_agent_session_watch.py` 23/23 |
| `agent_access_bundle.py` `vibe` track | one-command probe of all three | live run `VIBE_*=0`, `overallExit=0`; `test_agent_access_bundle.py` 4/4 |
