# demo1 Lease Conflict Autoflow

Overlapping source-edit leases must not stall a task into repeated
"lease를 종료해 주세요" prompts. This flow detects overlap, splits blocked
vs free targets, continues the free work, and delivers exactly one standard
release request per conflict fingerprint. It never force-releases.

## When

- `agent_scope_lease.py claim`/`check` or
  `__patch_drop__/source_edit_session.ps1 -Action begin` reports a target
  conflict (exit 7), or `codex_work_checkpoint.py begin` fails with a
  `source-lease-*`/`source-owner-lease-required` reason. Those entry points
  already attach a `leaseConflictAutoflow` plan — use it before asking.
- Right before you would ask the user or another agent to end a lease.

## Do

1. `python -B scripts/lease_conflict_autoflow.py scan --root . --targets <files>`
   → per-lease `topic`, `leaseId`, resolved `ownerTaskId`, `ageSeconds`,
   `lastHeartbeatUtc`, `status` (`active` | `expired_unknown` | `finishing`).
   `finishing` = owner journal closed or a release request already pending.
2. `python -B scripts/lease_conflict_autoflow.py plan --goal-files <files>
   --task <myTaskId> --execute`
   → `proceed_without` / `blocked` / `user_prompt` / `auto_actions`.
   `--task` excludes my own leases and records the decision; without
   `--execute` the plan only computes (plus the once-only prompt marker).
   Add `--no-mark` for a pure preview (used by `check`/checkpoint hints).
3. Continue on `proceed_without` immediately: claim/begin only those targets.
   `blocked` targets wait for the owner's normal release; re-run `plan`
   later — the same fingerprint never re-prompts.
4. The standard request is delivered once per fingerprint:
   - `data/agent-handoff/codex-autonomy/<ownerTaskId>/LEASE_RELEASE_REQUEST.md`
     (fallback `data/agent-handoff/lease-conflict-autoflow/release-requests/`
     when the owner task dir cannot be resolved)
   - prompt marker `data/agent-handoff/lease-conflict-autoflow/prompted/<fp>.json`
   - optional device-bus `proposal_created` event referencing the file only.
   Standard phrasing (fixed):
   > 작업 <ownerTaskId>가 <files>를 예약 중입니다. 끝났으면 그 작업에서
   > source-edit lease를 정상 종료해 주세요. 강제 해제는 하지 않습니다.
   > 현재 작업은 겹치지 않는 파일만 계속합니다.
5. `expired_unknown` owner = 생존 확인 불가. Never force-release; the request
   doc tells the owner session to finish normally (`journal close` /
   `agent_scope_lease.py done --task <id>` /
   `source_edit_session.ps1 -Action end`).
6. While holding a lease, renew at progress boundaries so your owner state
   stays alive and release requests reach the real owner:
   `python -B scripts/lease_conflict_autoflow.py heartbeat --task <myTaskId>`
   (same as `agent_scope_lease.py heartbeat`; TTL 1–540 min).
7. Journal: `plan --execute --task` writes a `lease_conflict` event
   `{owner, files, action: continue_partial|await_release}` into your own
   journal — never into the owner's.

## Don't

- Never force-release, quarantine, or delete
  `__patch_drop__/source-edit-locks/*` for `expired_unknown`/foreign leases —
  dead-owner proof stays with `scoped-blocker-recovery`
  (`references/lease-lifecycle.md`, `-Action recover`).
- Never edit a target another task's lease still covers; proceed on free
  targets only (`target-scoped` overlap = exact or prefix either direction).
- Never re-ask the user for an already-prompted fingerprint; check
  `promptState`/`prompted/` markers first — re-ask spam is the defect this
  flow removes.
- Never write the owner's journal or their checkpoint/cycle dirs; the owner
  channel is `LEASE_RELEASE_REQUEST.md` only.
- Do not mix business-logic (F01 etc.) changes into a lease-autoflow commit.

## Validate

`python -B -m unittest scripts.test_lease_conflict_autoflow -v` —
fixture: fake overlapping lease → plan splits blocked/proceed, prompt fires
once, non-overlap `begin` succeeds, expired lease files are preserved.
