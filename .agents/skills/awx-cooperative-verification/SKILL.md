---
name: awx-cooperative-verification
description: "Use when shared-worktree agent edits must not trigger repeated mid-edit builds: writer edit_batch markers, deferred verification tickets, DEFERRED-is-never-PASS reporting"
---

# awx-cooperative-verification — 협업 지연검증 레일

Same checkout, several agents (Devin/Codex/Grok/Cline). One agent's mid-edit
source must not be "verified" as if it were stable input, and a pending
verification must never be reported as PASS. This skill attaches a thin
deferred-verification layer to the **existing** journal/lease machinery — it
does not replace it.

Contract source: `docs/diagnostics/coop-verify-0928/AWX_cooperative_verification_directive_2026-09-28.md`
(사본; 원본은 사용자 Downloads), implementation notes `00_PROBE.md` + `01_DESIGN_FIT.md`.

## Core rule (never invert)

> If another session is editing, the work is **not** verified. Defer verification
> (`DEFERRED`), leave a resumable **ticket**, and end the turn as
> `APPLIED_PENDING_VERIFICATION`. `DEFERRED`/`QUIESCING`/`WAITING_FOR_RUNNER`
> are never `VERIFIED_PASS`, and a command exit 0 on `status`/`request` is
> transport success — the JSON `state`/`verificationStatus` field is the verdict.

## States

`APPLIED_PENDING_VERIFICATION` (report state) · `DEFERRED` · `QUIESCING` ·
`VERIFYING` · `VERIFIED_PASS` · `FAILED` · `INVALIDATED` · `ENVIRONMENT_ERROR` /
`TIMEOUT` · `BLOCKED_UNKNOWN_OWNER` · `WAITING_FOR_RUNNER` · `SUPERSEDED`.

## Command surface — `scripts/coop_verify.py`

```text
status                 world view + per-agent turnEnd block (exit 0 = read ok)
writer-begin           register an edit_batch BEFORE first write; returns token
writer-heartbeat       liveness only — never refreshes source-quiet timers
writer-checkpoint      logical batch closed; yields verify opportunity
writer-end             batch done (never implies success)
request                store/merge a durable verify ticket (exit 0 != PASS)
run-once               verify one eligible ticket; exit 0 == VERIFIED_PASS only
watch                  single-runner loop consuming tickets (needs real process)
recover                bounded reclaim of provably-dead writers/verifier locks
```

All commands print JSON on stdout. `--root` repo root, `--store` overrides the
default `data/agent-handoff/coop-verify/`, `--set name=seconds` shrinks timing
knobs for tests. run-once exit codes: `0` VERIFIED_PASS · `10` DEFERRED/QUIESCING ·
`11` INVALIDATED · `20` FAILED · `21` ENVIRONMENT_ERROR/TIMEOUT ·
`30` BLOCKED_UNKNOWN_OWNER · `44` NO_PENDING_TICKET.

## Agent flow

1. Before the first write of a change-set: `writer-begin` (paths + task +
   optional `--lease-id` of the existing source-edit lease). One batch ≠ one
   file save; a session can hold several sequential batches.
2. While editing: `writer-heartbeat` (or `--source-changed` when a scoped file
   was actually saved).
3. Logical batch boundary: `writer-checkpoint` (not every PostToolUse) — this is
   what yields the shared tree to pending verification.
4. Before building/testing: `request` a ticket (profile + scope +
   `--command`/`--command-json`), then `run-once`. Foreign `EDITING`/`CHECKPOINT`
   writers or an unmet quiet window → `DEFERRED`/`QUIESCING`; the ticket
   persists and a later `watch`/`run-once` resumes it.
5. Turn end: `status --agent <me>` → `turnEnd`. My writers all `RELEASED` +
   preserved ticket(s) → report `APPLIED_PENDING_VERIFICATION` and stop.
   Still-`EDITING` writers → `allowedToEnd=false`: checkpoint or end them first.
6. No live `runner.json` → tickets report `WAITING_FOR_RUNNER`; do not claim
   unattended resume works.

## Hard rules

- File ownership stays with `__patch_drop__/source_edit_session.ps1` leases +
  `work_journal.py`; coop writer records are scheduling markers that *reference*
  leases (`leaseId`), never a second truth. No `busy.lock`, no new server/MCP.
- heartbeat expiry is **not** completion: stale writer + dead owner pid →
  `recover` marks `BLOCKED_UNKNOWN_OWNER`; nothing is auto-released or passed.
- quiet period counts `lastSourceChangeAtUtc`; heartbeats never reset it.
- `run-once` exit 0 only after required stages really ran (receipt with
  command/argv/exit/input-hashes under `receipts/`). Mid-verify edits of scoped
  paths → `INVALIDATED`, not PASS.
- Do not keep retrying a deterministic failure (`FAILED` stays evidence);
  no LLM calls in wait loops; no mass process kills; no glasses/runtime restart.
- Verification never edits the source it measures.

## Storage

`data/agent-handoff/coop-verify/` — `state.json` (writers/tickets/runner,
mutated only under `.coop.lock`), `.verify.lock` (single heavy verifier),
`runner.json`, `receipts/<ticketId>.json`. No secrets, no transcripts.

## Parallel-lane preflight (additive)

Before the first write on a shared-goal session, run
`python -B scripts/codex_parallel_preflight.py --root . --goal-key <key>
--scope <paths> --agent <name> --json` — its `liveWriters[]` reflects open
edit batches here, and `nextCommands[]` includes `writer-begin` so a batch is
never forgotten. See `.agents/skills/demo1-codex-parallel-lanes/SKILL.md`.
