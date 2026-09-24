---
name: demo1-git-vibe-workflow
description: Use when a demo-1 vibe ask means '커밋해'/'git 정리' — own paths, scan, one local commit
---

# demo1-git-vibe-workflow

Thin orchestrator for "commit this / git cleanup" asks inside a demo-1 vibe
session. It composes the existing gates — it is not a new VCS and grants no
extra authority.

## When

- The user asks to commit, review staging, or tidy Git state during vibe work
  on this root. Pure read-only asks route through
  `$demo1-conditional-local-git` directly; this skill owns the end-to-end
  commit flow.

## Do

1. Preflight: `python -B scripts/agent_preflight.py --root .` — confirm the
   canonical root, check journals/leases for foreign Git work, note any live
   `git.exe` writer (a scoped hold, never a repository-wide stop).
2. Ownership: pass only paths this session's journal/lease owns as `--path`;
   never `add -A`/`add .`, never touch another session's staged paths (they
   are preserved byte-identical, not unstaged).
3. One call:
   `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned>... --message-file <file> [--task-id <id>]`
   — check → stale `index.lock` soft-clear → owned add → staged scan → one
   commit; stdout is one JSON line, `outcome=committed` (`committed=<sha>`) or
   `outcome=deferred` (`deferred=<reason>`). `--dry-run` prints the plan only;
   `--task-id` journals one `AUTO:` line. The message file carries
   `이유:`/`검증:`/`제약:` (or `Reason:`/`Verify:`/`Constraint:`).
4. On `deferred` report the reason code and stop — `index-lock`,
   `missing-blob`, `secret-found`, `blocked-path`, `blast-radius-paths`,
   `blast-radius-deletions`, `foreign-or-mismatched-staging` are all
   diagnosable; do not broad-add or clean up a mass of D/?? paths.
5. Report: commit id or deferred reason, leftover foreign staged paths,
   holds — never secret values.

Low-level equivalent (the orchestrator wraps it): `git add <explicit paths>`
then `conditional_local_git.py scan --repo . --path <each staged path>` then
`commit --repo . --message-file <file> --path <p>...`; orchestrator default
maps to `commit --preserve-foreign-staged` (`--strict-staging` = exact-match).

## Do not

- Do not mix a local commit with GraphRAG/Nova/Meta Display or other large
  patch seams — separate change-sets, separate checkpoints.
- No push/pull/fetch/merge/rebase/reset/clean/stash/remote or history rewrite;
  a publish/remote ask needs its own explicit user authorization.
- No `git tag`/annotated tags, no VERSION/CHANGELOG/RELEASE root files, no
  semver bumps, no `gh release`, no versioned artifact uploads — agents ship
  local selective commits only; a version/release structure is never part of
  this flow (a real external consumer needing a milestone tag is a human
  decision, not an agent branch).
- Never delete `.git`/`index.lock` by hand, never kill `git.exe`; a stale
  0-byte `index.lock` is only *moved aside* by the `lock` gate or the
  orchestrator — any unmet condition preserves it and reports BLOCKED.
- One commit per authorized scope — do not chain commits without a new ask.

## Related

- `$demo1-conditional-local-git` — command gate + policy (`check`/`scan`/`commit`).
- `$demo1-git-secret-guard` — secret scan internals and finding codes.
