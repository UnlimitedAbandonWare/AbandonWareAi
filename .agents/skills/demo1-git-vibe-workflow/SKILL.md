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
2. Ownership: stage only paths this session's journal/lease owns —
   `git add <explicit paths>`; never `add -A`/`add .`, never touch another
   session's staged paths (report them instead).
3. Scan: `python -B scripts/conditional_local_git.py scan --repo . --path <each staged path>`
   (pre-commit equivalent: `scripts\git_secret_guard.ps1 -Mode pre-commit`).
   Non-zero exit → stop and report the reason code.
4. Commit once:
   `python -B scripts/conditional_local_git.py commit --repo . --message-file <file> --path <path>...`
   — the message file carries `이유:`/`검증:`/`제약:` (or `Reason:`/`Verify:`/
   `Constraint:`).
5. Report: commit id, scan verdict, leftover foreign staged paths, holds —
   never secret values.

## Do not

- Do not mix a local commit with GraphRAG/Nova/Meta Display or other large
  patch seams — separate change-sets, separate checkpoints.
- No push/pull/fetch/merge/rebase/reset/clean/stash/remote or history rewrite;
  a publish/remote ask needs its own explicit user authorization.
- Never delete `.git`/`index.lock`, never kill `git.exe`; a stale 0-byte
  `index.lock` is reported to the user, not removed.
- One commit per authorized scope — do not chain commits without a new ask.

## Related

- `$demo1-conditional-local-git` — command gate + policy (`check`/`scan`/`commit`).
- `$demo1-git-secret-guard` — secret scan internals and finding codes.
