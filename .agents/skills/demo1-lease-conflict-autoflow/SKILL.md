---
name: demo1-lease-conflict-autoflow
description: 'Use when overlapping source-edit leases block a task: classify each blocking lease as live / stale / orphan, recover only proven-dead owners, continue free targets, and leave one standard release request per conflict fingerprint — live leases are never force-released.'
---

# demo1 Lease Conflict Autoflow

Overlapping source-edit leases must not stall a task into repeated
"lease를 종료해 주세요" prompts — and must not turn the user into a
messenger between sessions. This flow classifies each blocking lease as
**live | stale | orphan**, preserves unknown owners, continues free
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
   `user_prompt`, `auto_actions`. `--execute` uses native recovery for expired
   overlaps with fresh same-host dead-owner proof (receipt + `recover` event +
   `AUTO:lease-reclaimed=` journal), then recomputes. Unknown/remote/corrupt
   owners remain blocked; expiry is not recovery authority.
   Without `--execute` the plan only computes (plus the once-only prompt
   marker); `--no-mark` = pure preview (used by `check`/checkpoint hints).
3. Continue on `proceed_without` immediately: claim/begin only those targets.
   `claim` already runs this execute-plan on a conflict and retries `begin`
   once after a successful stale reclaim.
4. Manual reclaim of known leftovers:
   `python -B scripts/lease_conflict_autoflow.py reclaim [--targets <files>]
   --task <myTaskId>` (`agent_scope_lease.py reclaim` is the same call;
   `--dry-run` previews, `--include-orphan` retains unreadable locks for review,
   `--stale-grace-seconds` default 120). Execution reuses
   `source_edit_session.ps1 -Action recover -RecoveryLockName <exact-lock>
   -LeaseFingerprint <current-sha256>`; it cannot recover other locks.
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
   **live lease → 대기 후 이어서 (D32):** BLOCKED로 끝내지 않는다.
   `plan`이 `nextAction: "WAIT"` 또는 `"WAITING"`과 `waitCommand`를 내면 그 명령을
   실행한다:
   `python -B scripts/codex_auto_unblock.py lease-wait --paths <blocked>
   --max-min auto --enqueue --task <myTaskId>`
   - `--max-min auto`: 막은 lease의 상태로 예산 결정 — finishing/
     releasePending+heartbeat 정상이면 상대 만료+5분, active면 20분,
     stale이면 기다리지 않음(상한 60분). 숫자 `--max-min N`도 그대로 동작.
   - `--enqueue`: `__patch_drop__/source-edit-locks/waiters/<sha12>/
     <UTC>-<task>.json` 대기표 — 같은 파일을 기다리는 세션은 순번대로만
     진행(만료 대기표는 무시, 내 것만 생성·삭제).
   - `--task`: 대기 중 backoff poll마다 내 lease heartbeat 갱신. elapsed는 monotonic,
     sleep은 60초와 round의 남은 deadline으로 제한한다. round 소진은 목표 종료가 아니다.
   - `--dry-run`은 1회 스캔만(대기표도 안 만든다, 완전 읽기 전용).
   대기 중에는 사용자에게 "기다릴까요?"를 묻지 않고, 막히지 않은 파일
   작업·테스트 준비·막힌 파일용 패치를 ledger 아래 `.diff`로 미리 작성
   (실제 파일엔 안 씀)까지만 한다. 대기 시작·끝에 journal `LEASE_WAIT_START`/
   `LEASE_WAIT_END` 1줄씩.
   풀리면(result=free) `plan --waited`로 재계획 → `nextAction: "RESUME"` +
   `resumeAllowed=false`와 `resumeChecks` 5개를 낸다: 자기 fresh begin receipt 획득 →
   immutable baseline과 최신 target/test/config full SHA256·path identity 비교 →
   재독/새 계획/RED 또는 현재 검사로 already-done 확인 → strict pre-edit verify →
   변경에 묶인 현재 검사. 이는 advisory 계획이며 free가 쓰기 권한은 아니다.
   예산이 끝났는데 live면 `plan --waited`는 `nextAction: "WAITING"`과
   `resumeWhen: [{leaseId,topic,expiresAtUtc}]`를 유지한다. 다음 round를 관측하며
   독립 범위를 계속한다. stale/orphan/unknown은 lifecycle SSOT에 따라 해당 lane만 보류한다.
   여러 Devin/Codex를 동시에 돌릴 때 ChatWorkflow.java처럼 자주 겹치는 파일은
   쓰는 에이전트 1개만 배정하고, 나머지는 읽기·테스트·`.diff` 준비 역할로 둔다.
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
- Never reclaim on TTL alone. Native recovery proves same-host PID/start
  death and re-verifies lock inventory, fingerprint, root and heartbeat
  before movement; a refreshed or invalid heartbeat stops the move.
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
fixtures cover: unknown/remote/corrupt owners preserved in preview and execute,
proven-dead recovery, targeted scope and generation replacement, fresh heartbeat
and receipt-time refresh protection, grace-window hold, once-only prompt, and
exact foreign lease byte preservation.

## Parallel-lane preflight (additive)

Before the first edit on a goal that other chats may share, run
`python -B scripts/codex_parallel_preflight.py --root . --goal-key <key>
--scope <paths> --agent <name> --json` — it reports `FOREIGN_CLAIM_OVERLAP`
with `blockingClaims` + `freeScope`, and `STALE_CLAIM` candidates as a
dry-run only (reclaim stays with this flow's `reclaim`). See
`.agents/skills/demo1-codex-parallel-lanes/SKILL.md`.

## Overlapping lease = auto-resume, never a stop (additive)

An overlapping live lease must not end the session: lease-wait while doing non-overlapping work, then lease_resume_check.py exit code (0 continue / 10 re-plan+resume / 20 hold that file only). SSOT: $demo1-parallel-auto-resume (.agents/skills/demo1-parallel-auto-resume/SKILL.md).
