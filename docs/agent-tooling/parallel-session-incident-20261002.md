# Parallel-session incident — 2026-10-02 (duplicate goal, unclaimed edits)

Contract: DEMO1-DEVIN-CODEX-PARALLEL-LANES-20261002 (DV1).
Evidence source: two real journals under
`data/agent-handoff/codex-autonomy/` (read-only, SHA-invariant),
plus anonymized fixtures in `scripts/fixtures/parallel_lanes/`.

## Timeline (KST, reconstructed)

| Time | Event |
|------|-------|
| ~13:28 | The same directive `[Contract: DEMO1-CODEX-CHAT-TIMEOUT-FAILOVER-V2-20261002]` is pasted into two separate Codex desktop chats. |
| 13:28:23 | Journal `codex-chat-timeout-failover-v2-26e76ed3` opened (agent `codex-chat-timeout-v2`), status `in_progress`. |
| 13:32:12 | Journal `codex-chat-timeout-failover-v2-d542bf32` opened (agent `codex-timeout-v2-root`) on the **same contract**. |
| 13:33:54 | Second journal notes `"Existing same-goal journal 26e76ed3 treated as foreign; own new journal"` — the duplicate was *detected* and *proceeded anyway*. |
| 13:36–13:44 | Both chats edit overlapping Java/test files. `agent_scope_lease.py who` shows **0 claims** for either task; `coop_verify.py status` shows **0 writers**. |
| ~13:48 | User stops the second chat. An approved chat-to-chat message hands off the work: smoke allowance used, owned changes, sealed checkpoint cycle, remaining failures, 4 reviewer defects, and how to verify. Journal `d542bf32` closes `partial` — "Goal acceptance remains unverified". |
| after | The surviving chat continues under journal `26e76ed3` and later acquires a source lease via `source_edit_session.ps1 -Action begin`. |

## plannedScope comparison

`26e76ed3` (7 entries):

- `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java`
- `main/java/com/example/lms/config/LocalLlmProcessManager.java`
- `main/java/com/example/lms/llm/OllamaNativeChatModel.java`
- `main/java/com/example/lms/llm/TimedChatModelCaller.java`
- `main/java/com/example/lms/service/ChatWorkflow.java`
- `src/test/java`
- `docs/PROJECT_STATUS.md`

`d542bf32` (8 entries): the same 7 **plus**
`main/java/com/example/lms/web/PageController.java`.

Overlap = 7/7 of the first journal's scope — a full collision, not a lane split.
Neither journal carried a `[LANE: planId/laneId]` marker, so nothing separated
their write sets.

## Why detection failed (root cause chain)

1. **No claim gate**: both chats edited without
   `agent_scope_lease.py claim`, `coop_verify.py writer-begin`, or
   `source_edit_session.ps1 -Action begin`. `who` = 0 claims, writers = 0 —
   there was nothing for an overlap check to observe.
2. **Duplicate noticed, not enforced**: the second journal's own event proves
   it saw `26e76ed3`, classified it "foreign", and kept going. Goal-key
   matching existed nowhere as a tool rule; it was a judgement call.
3. **No stale/quiet distinction**: had the second chat wanted to continue, the
   correct roles were `VERIFIER` (peer live) or `TAKEOVER` (peer quiet /
   handoff recorded) — neither was computable without this kit.
4. **Checkpoint evidence unused**: both chats sealed checkpoint cycles, but no
   tool compared postimage SHAs against current bytes to attribute or detect
   unclaimed drift.
5. **Handoff worked by luck**: the rescue succeeded because the user manually
   approved a chat-to-chat message. There was no `handoff.json` schema the
   survivor could machine-verify.

## What this kit adds (mapped to the failures above)

| Failure | Tool / rule |
|---------|-------------|
| 1, no claim gate | `codex_parallel_preflight.py` lists `nextCommands[]` = claim + writer-begin + quota acquire before edits |
| 2, duplicate proceeds | `role_decision` → `DUPLICATE_GOAL_LIVE` + role `VERIFIER` |
| 3, no quiet/handoff roles | `DUPLICATE_GOAL_QUIET` / `DUPLICATE_GOAL_HANDOFF` → `TAKEOVER` with `baseline.postimages` |
| 4, drift undetected | `unclaimed_edits` (UNCLAIMED_EDIT) + integrate-gate postimage drift check |
| 5, handoff ad-hoc | `handoff-packet-example.json` fixture = minimal `work_journal.py handoff` schema (`fromTask`, `lastCheckpoint`, `ownedChanges[]`, `smokeUsed`, `openItems[]`, `findings[]`, `atUtc`, …) |

Plus lane partitioning (`codex_lane_plan.py`), shared-resource quotas
(`codex_lane_quota.py`), the read-only board (`codex_lane_board.py`), and the
integration gate (`codex_lane_integrate.py`) described in
`codex-parallel-sessions.md` / `codex-parallel-quickstart-ko.md`.

## Invariants

- Original journals `26e76ed3`, `d542bf32`: **read-only**; this doc cites, never modifies.
- `who` / claim / coop state before and after every detection run: identical
  (all detection is dry-run; reclaim stays with `agent_scope_lease.py reclaim`).
