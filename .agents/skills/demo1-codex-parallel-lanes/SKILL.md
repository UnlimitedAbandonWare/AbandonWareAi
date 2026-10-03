---
name: demo1-codex-parallel-lanes
description: >-
  Use when several Codex/Devin chats intentionally run at once on this
  checkout: split one goal into non-overlapping lanes, detect duplicate goals
  and unclaimed edits before the first write, meter shared resources
  (server restart, /chat smoke, Gradle, Ollama GPU), and gate the final merge.
  Triggers: 여러 Codex 채팅 동시 실행, 레인, 병렬 세션, 인계.
---

# Codex Parallel Lanes — 병렬 세션 운영 키트

Several chats on one shared tree are safe only when write scopes are
partitioned and shared resources are metered. This kit composes the existing
journal / scope-lease / coop-verify / checkpoint machinery — it is **not** a
new lock system and never a resident daemon.

## When

- The user pastes one directive into 2+ Codex chats, or asks for
  레인 분할 / 병렬 세션 / 인계 운영.
- Before the first edit when another chat may hold the same goal
  (`DUPLICATE_GOAL_LIVE` protection).

## Steps

1. Plan once: `python -B scripts/codex_lane_plan.py --brief <지시서>`
   (or `--wp "name=path1,path2"` repeated) →
   `data/agent-handoff/parallel-lanes/<planId>/` gets `plan.json`,
   `lane-<id>.txt`, `PLAN_KO.md`. Same-file WPs merge into one lane;
   unresolvable overlap answers `SERIAL_REQUIRED` (exit 5).
2. One chat per lane; paste `lane-<id>.txt` on top of that chat's directive.
3. Each chat's first action —
   `python -B scripts/codex_parallel_preflight.py --root . --goal-key <key>
   --lane <planId>/<laneId> --scope <paths> --agent <name> --json`:
   `CLEAR→OWNER` proceed · `DUPLICATE_GOAL_LIVE→VERIFIER` read-only ·
   `DUPLICATE_GOAL_QUIET|HANDOFF→TAKEOVER` baseline = peer checkpoint
   postimages · `FOREIGN_CLAIM_OVERLAP→WAIT` (`freeScope` only).
4. Then the existing gates: `agent_scope_lease.py claim` →
   `coop_verify.py writer-begin` → `codex_lane_quota.py acquire` for
   chat-smoke / server-restart / gradle / ollama-gpu-load.
5. Handoff/close through `work_journal.py handoff` (`handoff.json` schema:
   `scripts/fixtures/parallel_lanes/handoff-packet-example.json`).
6. INTEGRATOR lane last:
   `python -B scripts/codex_lane_integrate.py check --plan <planId> --json` →
   `READY_FOR_INTEGRATION_TEST` prints the combined test command;
   `BLOCKED(reasons)` lists open journals / postimage drift / violations.

## Board (read-only)

`python -B scripts/codex_lane_board.py --plan <planId> --json` — per-lane
role, chat, last activity, claimed files, smoke/restart usage, journal status.

## Forbidden

- Editing outside `write=` scope (violations surface as `LANE_VIOLATION`;
  `docs/PROJECT_STATUS.md` is INTEGRATOR-reserved).
- VERIFIER lanes writing anything; quota overuse (chat-smoke plan total
  default 2, restart/GPU mutex, gradle ≤2 with unique buildHostId).
- Reclaiming live foreign claims/leases — stale candidates are dry-run only;
  real reclaim stays with `agent_scope_lease.py reclaim`.
- Sending chat-to-chat messages without user approval; handoff evidence is
  `handoff.json`, not the message.

## Evidence / docs

- Incident reconstruction + anonymized fixtures:
  `docs/agent-tooling/parallel-session-incident-20261002.md`,
  `scripts/fixtures/parallel_lanes/`.
- Investigation (shared-tree vs worktree): `docs/agent-tooling/codex-parallel-sessions.md`.
- Korean quickstart: `docs/agent-tooling/codex-parallel-quickstart-ko.md`.
- Tests: `scripts/test_codex_parallel_preflight.py`, `test_codex_lane_plan.py`,
  `test_codex_lane_quota.py`, `test_codex_lane_integrate.py`
  (golden scenarios 1–12).
