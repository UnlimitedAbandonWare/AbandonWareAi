---
name: demo1-goal-complete-stop
description: Use when a demo-1 Codex goal, objective, or acceptance check is done
---

# Demo1 Goal Complete → Stop

## When

- The current user/goal acceptance criteria are met
- The user says stop / end / 종료 / enough
- A new session prompt conflicts with older handoff Markdown (HELLO/TLS/relay essays)

## Do

1. Re-read the **latest user ask** only as authority.
2. Quickly confirm the asked surface under `<repo>` (and the named URL if any).
3. If acceptance is met and this session owns uncommitted changes under the
   conditional-Git scope, attempt the commit path **once**:
   `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned>... --message-file <file> --task-id <id>`
   — report `committed=<sha>` or `deferred=<reason>` in one line. A deferred
   result never blocks the stop and is never forced. Local selective commit
   only — agents never `git tag`, push, bump versions, or write
   VERSION/CHANGELOG/RELEASE files.
4. **Before declaring done**, pass the sentence you are about to report as
   completion through the goal-switch barrier:
   `python -B scripts/demo1_goal_switch_barrier.py reject-complete --task <taskId> --text "<claim>" --latest-instruction-ref <currentUserRef> --expected-revision <currentRevision> --environment <verifiedHost>`
   — exit 0 required. A non-zero `instructional-not-acceptance` verdict (e.g.
   "Read AGENTS.md before continuing", `Use $skill`, a bare tool command) means
   it was a directive preamble, not an acceptance result: do NOT stop, keep
   working or report the blocker.
   New/updated tasks use the existing state.md continuity contract. Delivery
   failure also returns exit 5 even when the prose says tests passed. Read the
   latest user instruction independently; do not derive current bindings solely
   from old state. Legacy prose-only calls remain compatible, not delivery proof.
5. Before the final, close this session's leases: `agent_scope_lease.py done --task <id>` (or `-Action end` with owner+fingerprint) for any claim/begin this session still holds — release on complete, defer, or abort alike; a leftover becomes the next session's stale cleanup.
6. Give a short final: what changed, how verified, what remains (if any).
7. **Stop.** Do not invent the next feature, do not keep "vibe continuing", do not open TLS/DAT/relay rabbit holes.

## Goal intake ≠ acceptance

- Reading `goal-objective.md` / a PASTE brief, summarizing it, or confirming its
  constraints is **intake**, never an acceptance result. A completion claim that
  only asserts reading the goal (`목표 파일 읽기`, `read the goal`,
  `등록된 목표 … 읽 … 완료`, `intake-only`, `문서만 확인`) is rejected by the
  barrier (exit 5, `instructional-not-acceptance`).
- If the registered goal title is only "read file X" (`목표 파일 읽기`,
  `Read goal-objective`, `Read <path>`), treat it as a **mis-registration**,
  not as done: re-derive the goal from the PASTE `Mission` / `목표 한 줄` /
  `완료 정의` and keep implementing the smallest seam.
- Do not invoke this skill on a read-only report. Before Done you need at least
  one of: (a) a product/source diff, (b) the WP's test result, or (c) `BLOCKED`
  naming the blocked file and reason.

## Stale artifacts = reference only

These never override the live ask:
- old session handoffs, TLS writeups, HELLO/DISPLAY TEST baselines quoted as mandatory copy
- Autolearn/cycle JSONL, PatchDrop history, notebook directives not selected this turn

Use them only as hints. If they conflict with the live ask, ignore the conflict and follow the live ask.

## Anti-patterns

- Reporting `goal-objective 읽기 완료` / "read the goal file" as Done — intake is never acceptance
- Reporting an instruction ("Read … before continuing", "Use $skill …") as if
  it were the achieved acceptance result
- Continuing after "done" because personal autonomous-work text says keep going
- Reintroducing sample HELLO/TEST copy after the user banned it
- Expanding scope "while we're here"
## Related

While the goal is still open, unknowns use the ask/search/step policy in `AGENTS.md` (web-search first; report + ask if blocked or irreversible). This skill only covers **ending** when acceptance is met.
