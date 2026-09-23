# demo1 Agent Change Plane

Cross-agent (Devin / Grok / Codex / Cline) ChangeIntent board layered on top of
the existing source-edit lease. It declares intent, admits non-overlapping work
through the real `begin`, renews via the real `heartbeat`, records an
append-only event log, and delivers at most one release request per conflict
fingerprint. It never locks or unlocks anything itself -
`__patch_drop__/source_edit_session.ps1` stays the only lock authority.

## When

- Before any source write that could overlap a parallel agent session on this
  checkout (same Project Root, any agent label `devin-|codex-|grok-|cline-`).
- When `admit`/preflight reports a foreign lease on your target and you would
  otherwise ask the user to end it.
- When you need the shared board: who owns `runtime`/`build` surfaces, which
  intents are blocked, what events already happened.

## Do

1. Declare before writing:
   `python -B scripts/agent_change_plane.py propose --agent <label> --task <taskId> --path <repo-rel> [--surface runtime|build]`
   (`--manifest <file>` for the same `{targets:[{path,sha256}]}` shape as the
   PS1 TargetManifest; a pinned sha256 is the preimage check at `admit`).
2. `python -B scripts/agent_change_plane.py admit --intent <ci-...>`
   Atomic: overlap -> `blocked` (exit 7, no lock written); clear -> calls
   `source_edit_session.ps1 -Action begin` itself and issues a monotone `fence`
   (`f-N`). Keep the fence: `renew`/`seal`/`end` reject mismatched fences.
3. Renew at progress boundaries:
   `python -B scripts/agent_change_plane.py renew --intent <ci-...> --fence <f-N>`
   (delegates to `-Action heartbeat`; it does not reimplement TTL).
4. Done writing: `seal` (records verify/postimage state) then
   `end --fence <f-N>` (delegates to `-Action end`).
5. Read the board: `status` (active journals + lease summary + port leases +
   intents + `owners.runtime`/`owners.build` + `lastEventSeq`),
   `events` (`data/agent-handoff/change-plane/events.jsonl`, append-only),
   `plan` (`proceed[]`/`blocked[]`; emits the once-per-fingerprint release
   request for blocked intents).
6. `request-release --intent <ci-...>` or `--path <p>` writes
   `LEASE_RELEASE_REQUEST.md` into the owner task dir (fallback
   `data/agent-handoff/change-plane/release-requests/`) plus one event.
   Repeat calls on the same conflict fingerprint are suppressed.
7. `expired_*` in `status` means the TTL lapsed while the lease still occupies
   its scope - keep treating it as blocking; only the owner (or proven-dead
   recovery via the PS1 contract) ends it.
8. Journal: propose/admit/end drop best-effort notes into *your own* task
   journal. Pair with `agent_preflight.py` (`changePlane` field) at entry.

## Don't

- Never delete or hand-edit `__patch_drop__/source-edit-locks/*`, never invent
  PS1 action names (`open` does not exist; valid: `begin|end|status|verify|
  bind-scope|heartbeat|recover`).
- Never force-release a foreign lease and never close another task's journal.
  Expired-TTL is not owner-death proof.
- Do not write lease/lock files directly - `admit` is the only write path and
  it always goes through the PS1 `begin`.
- Do not take `runtime` or `build` surface work while `status` shows a live
  foreign holder (`surface-conflict` blocks without touching leases).
- No Redis, no repo-global lock, no worktree-wide hold: conflict scope is
  per-path (exact or prefix either direction).
- Do not treat `LATEST.md`/`events.jsonl` as locks - they are the board/log;
  the PS1 lease rows remain the truth for overlap.
