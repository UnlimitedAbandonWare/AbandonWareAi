# Devin 붙여넣기 — AM-01 Sticky ChangeIntent reconcile (2026-09-27)

너는 Devin이다. 제품 기능이 아니라 **에이전트 기전**만 고친다.

## Context
Probe Top10 → shortlist3 → THE ONE:
1. AM-01 Sticky blocked ChangeIntents (지금)
2. AM-02 Triple write stack (나중)
3. AM-03 Zombie in_progress journals (나중)

Evidence: `agent-prompts/_probe/agent-mechanisms-unnatural-20260927/FINDINGS.md`
Live smoking gun (probe time): intents blocked 4/7; blocking owners `rtx3090-…`, `mutable-spec-…`, `chat-video-…` journals **closed**; live locks only `clean-*` unrelated.

Existing tool: `scripts/agent_change_plane.py` (propose/admit/renew/seal/end/status/events/plan/request-release/preflight).
Already has `evaluate_open_intents` / unblock path — still leaves sticky board. Skill: `.agents/skills/demo1-agent-change-plane/`.
Tests: `scripts/test_agent_change_plane.py`.

## Goal
Ghost `blocked` intents must not gate new admits after owner journals are closed and no overlapping live PS1 lock remains.

## Do (최소)
1. Implement `python -B scripts/agent_change_plane.py reconcile [--dry-run]`:
   - For each intent with `state=blocked`:
     - Resolve `blockingLeases[].ownerTaskId` (and fingerprint owners).
     - If **every** blocking owner journal is closed/missing **and** no live overlapping lock under `__patch_drop__/source-edit-locks` for those paths/topics → transition to `superseded` or `expired` (pick one, document), append event, keep history.
   - If any live overlapping lock remains → leave blocked.
   - Never call PS1 `end` on foreign leases; never delete intents/events.
2. Ensure `status`, `admit`, and `plan` run this reconciliation (or call `evaluate_open_intents` fixed so closed-journal ghosts clear) **before** treating blocked as hard stop.
3. Dry-run first; write receipt under `data/agent-handoff/change-plane/` (e.g. `reconcile-receipt-<utc>.json`) with intentIds touched — no secrets.
4. Add/extend unit tests in `scripts/test_agent_change_plane.py` for: closed-journal ghost → superseded; live lock → stays blocked.
5. One short note in skill SKILL.md: board is derived; reconcile/auto-clear ghosts; PS1 remains lock authority.

## Verify
```text
cd C:\AbandonWare\demo-1\demo-1\src
python -B scripts/agent_change_plane.py status
python -B scripts/agent_change_plane.py reconcile --dry-run
python -B scripts/agent_change_plane.py reconcile
python -B scripts/test_agent_change_plane.py
python -B scripts/agent_change_plane.py status   # blocked count should drop for ghosts
```
Cite before/after blocked counts from status JSON/LATEST.

## Hard stops
- Product `main/java`, static JS, SecurityConfig, proto-open=false, admin harden
- Force-ending live `clean-*` or any foreign lease
- Collapsing AM-02 triple stack / AGENTS slim / plugin matrix (out of scope)
- `add -A`, push without separate ask, secret print
- Killing Java/Gradle; deleting madasin build dirs

## Done format
```
AM01_RECONCILE: DONE|PARTIAL
before_blocked / after_blocked
reconcile_changed: [intentIds…]
tests: pass|fail|NOT_RUN
files: […]
NOT_RUN: […]
```