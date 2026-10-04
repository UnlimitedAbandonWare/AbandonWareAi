---
name: demo1-lease-conflict-autoflow
description: >-
  Use when overlapping source-edit leases block a task: classify each blocking
  lease as live / stale / orphan, auto-reclaim stale ones, continue free
  targets, and leave one standard release request per conflict fingerprint —
  live leases are never force-released.
---

# demo1 Lease Conflict Autoflow

Overlapping source-edit leases must not stall a task into repeated
"lease를 종료해 주세요" prompts — and must not turn the user into a
messenger between sessions. This flow classifies each blocking lease as
**live | stale | orphan**, auto-reclaims stale ones, continues free
targets, and leaves exactly one standard release request per conflict
fingerprint for live owners. Live leases are never force-released.

## When

- `agent_scope_lease.py claim`/`check` or
  `__patch_drop__/source_edit_session.ps1 -Action begin` reports a target
  conflict (exit 7), or `codex_work_checkpoint.py begin` fails with a
  `source-lease-*`/`source-owner-lease-required` reason. Those entry points
  already attach a `leaseConflictAutoflow` plan — use it before asking.
- A leftover lease from a finished/abandoned session blocks your target
  (regression cases: `ma212in-catalog-recovery`,
  `rag-launcher-last-summary` — session ended without `end`, lease stayed,
  next agent got told "전달해 주세요").
- Right before you would ask the user or another agent to end a lease.

## Do

1. `python -B scripts/lease_conflict_autoflow.py scan --root . --targets <files>`
   → per-lease `topic`, `leaseId`, `lifecycle` (**live** = valid TTL /
   recent heartbeat / proven-alive owner; **stale** = expired with owner
   not proven alive; **orphan** = unreadable lock), `status`
   (`active` | `expired_unknown` | `finishing`), `ownerTaskId`,
   `expiredSeconds`, `lastHeartbeatUtc`.
2. `python -B scripts/lease_conflict_autoflow.py plan --goal-files <files>
   --task <myTaskId> --execute`
   → `staleReclaim` (quarantined/skipped), `proceed_without`, `blocked`,
   `user_prompt`, `auto_actions`. `--execute` first quarantines stale
   overlaps (receipt + `stale-reclaim` event + `AUTO:lease-reclaimed=`
   journal), then recomputes: only **live/orphan** leases stay blocked.
   Without `--execute` the plan only computes (plus the once-only prompt
   marker); `--no-mark` = pure preview (used by `check`/checkpoint hints).
3. Continue on `proceed_without` immediately: claim/begin only those targets.
   `claim` already runs this execute-plan on a conflict and retries `begin`
   once after a successful stale reclaim.
4. Manual reclaim of known leftovers:
   `python -B scripts/lease_conflict_autoflow.py reclaim [--targets <files>]
   --task <myTaskId>` (`agent_scope_lease.py reclaim` is the same call;
   `--dry-run` previews, `--include-orphan` also quarantines corrupt locks,
   `--stale-grace-seconds` default 120). Proven same-host dead owners also
   go through `source_edit_session.ps1 -Action recover`.
5. For **live** foreign leases the standard request is delivered once per
   fingerprint — to the owner's task channel, never as a user-relay ask:
   - `data/agent-handoff/codex-autonomy/<ownerTaskId>/LEASE_RELEASE_REQUEST.md`
     (fallback `data/agent-handoff/lease-conflict-autoflow/release-requests/`
     when the owner task dir cannot be resolved)
   - prompt marker `data/agent-handoff/lease-conflict-autoflow/prompted/<fp>.json`
   - optional device-bus `proposal_created` event referencing the file only.
   The user-facing line is a status report, not a relay request:
   > live lease: <owner> 예약 중 — <files> (lease 만료 <eta>).
   > 겹치지 않는 파일만 계속하고, 소유자 작업 채널에 정상 종료 요청
   > 1건을 남겼습니다.
6. `blocked` targets wait for the owner's normal release; re-run `plan`
   later — the same fingerprint never re-prompts. `finishing` = owner
   journal closed or a release request already pending.
   **live lease → 대기 후 이어서 (D32):** BLOCKED로 끝내지 말고
   `python -B scripts/codex_auto_unblock.py lease-wait --paths <files>
   [--max-min 20] [--interval 60] [--dry-run]`로 겹침이 풀릴 때까지
   재확인만 한다(해제·reclaim 없음, JSON `free|live|stale`). 풀리면 같은
   턴에 이어서 진행하고, 20분 후에도 live면 release 요청 1회 + partial
   종료 + handoff에 "재개 조건: lease <id> 해제 후 S<n>부터" 한 줄.
7. While holding a lease, renew at progress boundaries so your owner state
   stays alive and release requests reach the real owner:
   `python -B scripts/lease_conflict_autoflow.py heartbeat --task <myTaskId>`
   (same as `agent_scope_lease.py heartbeat`; TTL 1–540 min). On every exit
   path — complete/STOP, deferred/BLOCKED, idle/timeout, cancel — release
   with `agent_scope_lease.py done|abort --task <id>`; a lease left behind
   becomes someone else's stale cleanup.
8. Journal: `plan --execute --task` writes a `lease_conflict` event
   `{owner, files, action, reclaimedStale}` and each reclaim writes
   `AUTO:lease-reclaimed=<owner|reason>` into your own journal — never
   into the owner's.

## Don't

- Never force-release, quarantine, or hand-delete a **live** foreign lease
  (`valid TTL`, `liveHeartbeat`, or `ownerState=alive`) — that is the only
  hard rule. Never ask the user to relay "그 작업에 전달해 주세요" as a
  default response; user intermediation is reserved for a live lease that
  blocks urgent work long-term.
- Never reclaim on TTL alone without the tool: `reclaim` re-verifies lock
  inventory, lease fingerprint, root, heartbeat freshness and expiry right
  before the quarantine move (`--stale-grace-seconds` margin); a lease that
  refreshed between scan and move is left alone.
- Never edit a target another task's **live** lease still covers; proceed
  on free targets only (`target-scoped` overlap = exact or prefix either
  direction).
- Never re-ask the user for an already-prompted fingerprint; check
  `promptState`/`prompted/` markers first — re-ask spam is the defect this
  flow removes.
- Never write the owner's journal or their checkpoint/cycle dirs; the owner
  channel is `LEASE_RELEASE_REQUEST.md` only.
- Do not mix business-logic (F01 etc.) changes into a lease-autoflow commit.

## Validate

`python -B -m unittest scripts.test_lease_conflict_autoflow -v` —
fixtures cover: expired unknown-owner lease → reclaim quarantines it and
the target frees (begin→abnormal-exit→stale→reclaim→edit repro),
live lease and live-heartbeat lease never reclaimed, grace-window hold,
orphan flag gating, once-only prompt, and lease bytes preserved.

## Parallel-lane preflight (additive)

Before the first edit on a goal that other chats may share, run
`python -B scripts/codex_parallel_preflight.py --root . --goal-key <key>
--scope <paths> --agent <name> --json` — it reports `FOREIGN_CLAIM_OVERLAP`
with `blockingClaims` + `freeScope`, and `STALE_CLAIM` candidates as a
dry-run only (reclaim stays with this flow's `reclaim`). See
`.agents/skills/demo1-codex-parallel-lanes/SKILL.md`.
