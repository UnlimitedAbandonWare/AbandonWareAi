# Codex parallel sessions — investigation (DV6)

Contract: DEMO1-DEVIN-CODEX-PARALLEL-LANES-20261002.
Scope of observation: `~/.codex` **names and counts only** — no file contents,
no auth/token/config values. Scanner reused:
`scripts/agent_session_watch.py` (Codex store =
`sessions/**/rollout-*.jsonl` + `archived_sessions/**/rollout-*.jsonl`).

## 1. What this PC shows

`~/.codex` top-level directories observed (names only):

- `sessions/` — live rollouts; 1 top-level session dir at observation time.
- `archived_sessions/` — closed rollouts.
- `worktrees/` — 2 entries: Codex can run a goal in a HEAD-based worktree.
- `thread-writer-locks/` — 11 entries: per-thread write mutex state the
  desktop app keeps while a chat is writing.
- `attachments/`, `plans/`, `automations/`, `skills/`, `mcp-oauth-locks/`
  (presence noted, contents untouched), plus the usual app dirs.

Recent rollout function-call names (12 newest `rollout-*.jsonl`, names and
counts only — the shape of the desktop multi-chat surface):

| calls | tool | meaning here |
|------:|------|--------------|
| 122 | `wait` | agent polling between steps |
| 44 | `js` | page/script evaluation |
| 34 | `send_message` | chat-to-chat or user-facing message (approval-gated) |
| 28 | `followup_task` | scheduling continuation work |
| 6 | `spawn_agent` | starting a parallel agent/chat |
| 6 | `request_user_input_async` | ASK_ONCE-style prompt |
| 2 | `list_agents` | enumerating other chats |

This matches the observed incident flow: one chat listed and read the other
chat's goal, and after explicit user approval sent the handoff message.
**Implication:** each chat is an independent agent that *can see and message
siblings* — but nothing stops two chats from editing the same file at the
same time. Coordination must come from repo-local evidence (journals, claims,
leases, checkpoints), not from the app.

## 2. Shared-tree lanes vs worktrees

Working tree state at observation: `git status --short` = **1116 changed
paths** (≈1097 at DV0; the live foreign timeout task adds churn).

| | Shared tree + lanes (this kit) | HEAD-based worktree |
|---|---|---|
| Sees uncommitted changes | yes — that's the point | **no** — a worktree starts from HEAD; the 1100+ dirty files vanish. Clean/Cline already hit this. |
| Coordination | lane write-scopes + claims + quotas | VCS isolation (but Git writes are restricted here) |
| Shared resources (server, /chat, Gradle, GPU) | quota tool arbitrates | still shared — worktree does not help |
| Fit | **default for this checkout** | only for tasks whose scope is fully committed |

**Recommendation: shared-tree lanes.** A worktree's core promise (clean HEAD)
is precisely what this repo cannot provide; the 2026-10-02 incident was a
*duplicate-goal* problem, not a VCS problem, and lanes + preflight solve it
without moving a single byte. Mixed mode (worktree for an isolated,
fully-committed spike; lanes for everything else) is viable later — no
Codex setting change is proposed or made.

## 3. What the user actually does to run parallel Codex chats

1. **Plan once**: `python -B scripts/codex_lane_plan.py --brief <지시서> --lanes N`
   → `data/agent-handoff/parallel-lanes/<planId>/`에 `plan.json`,
   `lane-A.txt`, `lane-B.txt`, … `PLAN_KO.md`.
2. **Open one chat per lane** and paste the lane header (`lane-<id>.txt`)
   on top of the directive. Header contains `[LANE: planId/laneId of N
   role=… write=… smoke=n restart=yes|no]` — the write list is binding.
3. **Each chat's first action is `codex_parallel_preflight.py`** — it answers
   OWNER / VERIFIER / TAKEOVER / WAIT and prints the exact claim +
   writer-begin + quota commands. A `LANE_VIOLATION` or `DUPLICATE_GOAL_LIVE`
   means stop, not proceed.
4. **Chat-to-chat message approval**: allow only when a chat has *finished or
   is handing off* — the approval prompt is the handoff trigger
   (`handoff.json` schema in
   `scripts/fixtures/parallel_lanes/handoff-packet-example.json`). Approving
   mid-work messaging is fine but never substitutes for the packet.
5. **One INTEGRATOR chat** (usually the last opened) runs
   `codex_lane_integrate.py check --plan <id>` →
   `READY_FOR_INTEGRATION_TEST` gives the single combined test command line;
   `BLOCKED(reasons)` names the exact open lane / drifted file.

Full paste-ready Korean walkthrough:
`docs/agent-tooling/codex-parallel-quickstart-ko.md`.
