# 01 Codex weakness map — live MAX-PUSH evidence

Scope: live task `max-push-b-perf-0928-50df21d2` (agent `codex-max-push`). Every row is 사실 (verified in this checkout) unless marked 추정.

| ID | 증상 (observed) | live evidence | 조화 시드가 막을 것 |
|---|---|---|---|
| W1 | `PROJECT_STATUS.md` foreign write landed between read and append → CAS reject + hold | `journal.json` hold event 2026-09-28T12:28:32Z: "PROJECT_STATUS.md changed externally after checkpoint begin. CAS rejected own append" | T2 `scripts/agent_harmony_status_cas.py` — fresh-read→mutate retry loop + `rebaseGuide` JSON (wraps `status_doc.py` CAS; never overwrites foreign delta) |
| W2 | Track A verified while `runtime not_observed`; progress accumulates without runtime evidence | `max-push-progress-0928.md` §Evidence: "Runtime freshness for MAX edits: not_observed yet"; journal verify event "runtime not_observed" | T3 `scripts/agent_done_evidence_guard.py` — runtime claim without journal runtime evidence → `runtime-not-observed`, exit 2 |
| W3 | A2 fixture drift: stale 3-arg `AttachmentService` mock vs live 4-arg overload | journal verify 12:17Z "fixture still 3-arg, keep all product policy assertions"; progress row A2 GREEN | fixture-drift probe (candidate: extend `agent_code_evidence_gate.py`/`source_health_scorecard.py` — both mined heavily in seeds); not built here — 제안만 |
| W4 | GLM offload fails transport-side even with key (`SESSION_UNAVAILABLE`) | journal plan 12:12Z: "GLM key present but SESSION_UNAVAILABLE from unchanged transport" | offload result must not count as success — `$glm-offload` contract already says parent owns judgment; add reason-code passthrough (제안만) |
| W5 | intent match on exclusion text: orchestrator matched fold/wear playbook from words that appeared only inside exclusions | journal plan 12:12Z: "Orchestrator matched fold wear only from exclusion text; skip capture because explicit MAX-PUSH excludes glasses" — session handled it correctly, but the match itself was a false positive | intent/exclusion false-positive checklist before acting on a `matchedPlaybooks` hit (제안만; T4 kit §usage) |
| W6 | quarantine class itself: child/subagent sessions going stale with no evidence trail (the 9 rollouts) | `apply-9only.jsonl` class `child-stale-no-evidence` ×9; session `01a094ba` = GLM worker spawn w/ `early_done_no_cmd` flag | T3 guard `--task` mode: `no-verify-event` / `verify-ref-missing-run` rejections; `agent-session-watchdog` exists for live scanning |

## Observed, not patched here

- `LeaseReleaseRequest.md` queue: 14 requests addressed to `devin` in preflight signals — separate hygiene item, not this kit.
- Codex MAX-PUSH Track B (F01–F12) remains `pending` in `acceptance.json` — Codex owns that lane; this kit supplies only guard rails.
