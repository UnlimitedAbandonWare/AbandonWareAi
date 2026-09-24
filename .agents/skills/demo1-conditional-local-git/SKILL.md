---
name: demo1-conditional-local-git
description: Use when demo-1 work needs conditional local Git staging, staged scan, one local commit
---

# demo1-conditional-local-git

Conditional local Git gate for THIS canonical root only
(`C:\AbandonWare\demo-1\demo-1\src`). User authorization 2026-09-23 replaced
the blanket mutation ban here — nowhere else. Policy body:
`.grok/rules/demo1-conditional-local-git.md`; AGENTS.md
`DEMO1-GIT-LOCAL-FIRST` carries the same rules for Codex. Enforcement:
`scripts/conditional_local_git.py`.

## When

- `git status` / `git diff` reads, staging checks, or one local commit the user
  asked for ("커밋해", "commit this") inside a vibe session.

## Do

For an actual commit, any agent calls the orchestrator once — it wraps this
gate end-to-end (check → stale-lock soft-clear → owned add → scan → commit)
and prints `committed=<sha>` / `deferred=<reason>` as JSON:

```powershell
python -B scripts/agent_git_vibe_commit.py --repo . --path <owned-path> [--path ...] --message-file <file> [--task-id <id>] [--dry-run]
```

This file documents the low-level CLI the orchestrator wraps:

```powershell
python -B scripts/conditional_local_git.py policy            # print the policy body
python -B scripts/conditional_local_git.py check -- git status
python -B scripts/conditional_local_git.py check -- git add <path>
python -B scripts/conditional_local_git.py check -- git commit -F msg.txt
python -B scripts/conditional_local_git.py scan --repo . [--path <expected>...]
python -B scripts/conditional_local_git.py commit --repo . --message-file <file> --path <owned-path> [--path ...] [--preserve-foreign-staged|--strict-staging]
python -B scripts/conditional_local_git.py lock --repo . [--days 1.0] [--backup-dir <dir>]
```

- `check` verdicts: `allow-local` (read-only), `needs-scan` (add/commit — scan
  staged blobs first), `forbid` (exit 2). Never run a `forbid` command.
- `scan`/`commit` exit codes: 0 ok · 2 secret or foreign staging · 3 repo/
  policy/identity/staging problem · 4 `index.lock` present or index changed
  mid-scan.
- Stage only paths THIS session owns (`git add <explicit paths>`), then `scan`,
  then `commit`. The commit message file must carry all three label groups:
  `이유:`/`Reason:`, `검증:`/`Verify:`, `제약:`/`Constraint:`.
- Default `commit` is exact-match: the staged set must equal the `--path`
  list — another session's staged paths (`foreign-or-mismatched-staging`)
  stay untouched, out of your commit, and get reported, never unstaged by
  you. `--preserve-foreign-staged` (the orchestrator's default mode) adds and
  scans only the `--path` set in an isolated index, then publishes just those
  entries — foreign staging stays byte-identical (`foreignStagingPreserved`).
  `--strict-staging` names the exact-match contract explicitly.
- `index.lock`: agents never delete `.git` internals by hand. A proven-stale
  0-byte lock (age ≥ `--days`, no confirmed `git.exe` writer, unchanged index
  hash) is only *moved aside* by `lock` (`--backup-dir`) or by the
  orchestrator (`--stale-lock-days`, default 0.25 = 6h); any unmet condition
  → `action: preserved` + reason → BLOCKED, never force-delete.
- `missing-blob` (exit 3) means a staged index entry points at an object the
  store lost — integrity, not a policy block; it is reported separately from
  `blocked-path`. Foreign missing blobs do not block a
  `--preserve-foreign-staged` commit (the foreign index row is copied
  byte-identical).
- If `scan` or `commit` returns `blast-radius-paths` or
  `blast-radius-deletions` (candidate set >40 paths or >15 deletions), report
  that reason and stop. Do not broad-add, unstage, or commit a cleanup of the
  D/?? paths outside the owned set.

## Do not

- No `push`/`pull`/`fetch`/`merge`/`rebase`/`reset`/`clean`/`stash`/`init`/
  `remote`/history rewrite/`--amend`/`--no-verify`, no `add -A`/`add .`/
  `commit -a`, no `git config`/`credential`.
- Never print or commit secret values; never commit raw conversation,
  databases, models, indexes, or large logs.
- Never delete/rename/empty `.git` or `index.lock`, never kill `git.exe`.
- The working tree and passing tests stay the restore baseline — old HEAD is
  not a restore source, a clean tree does not prove the task is done, and
  `git status` never marks a file safe-to-delete.
- Application-source edits still need the normal owner/lease/preimage gates —
  this skill authorizes Git flow only, not GraphRAG/Nova/Meta Display source
  changes.

## Related

- `$demo1-git-secret-guard` — staged/working-tree secret scan internals.
- `$demo1-git-vibe-workflow` — thin vibe orchestration: preflight, own paths,
  scan, one commit, report.
