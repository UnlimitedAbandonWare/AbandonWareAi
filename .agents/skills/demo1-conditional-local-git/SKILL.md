---
name: demo1-conditional-local-git
description: Use when demo-1 work needs conditional local Git: staging, staged scan, one local commit
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

```powershell
python -B scripts/conditional_local_git.py policy            # print the policy body
python -B scripts/conditional_local_git.py check -- git status
python -B scripts/conditional_local_git.py check -- git add <path>
python -B scripts/conditional_local_git.py check -- git commit -F msg.txt
python -B scripts/conditional_local_git.py scan --repo . [--path <expected>...]
python -B scripts/conditional_local_git.py commit --repo . --message-file <file> --path <owned-path> [--path ...]
```

- `check` verdicts: `allow-local` (read-only), `needs-scan` (add/commit — scan
  staged blobs first), `forbid` (exit 2). Never run a `forbid` command.
- `scan`/`commit` exit codes: 0 ok · 2 secret or foreign staging · 3 repo/
  policy/identity/staging problem · 4 `index.lock` present or index changed
  mid-scan.
- Stage only paths THIS session owns (`git add <explicit paths>`), then `scan`,
  then `commit`. The commit message file must carry all three label groups:
  `이유:`/`Reason:`, `검증:`/`Verify:`, `제약:`/`Constraint:`.
- The staged set must equal the `--path` list — another session's staged paths
  (`foreign-or-mismatched-staging`) stay untouched, out of your commit, and get
  reported, never unstaged by you.
- `index.lock` (0-byte or stale, no proven writer): report it to the user.
  Agents never delete `.git` internals.

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
