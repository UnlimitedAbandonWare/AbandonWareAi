# DEMO1-GIT-READ-NO-LOCK — read-only git without index.lock contention

SSOT for keeping agent read-only git calls from competing for `.git/index.lock`
while the user commits in GitHub Desktop. Applies to this checkout only.

## Background

`git status` (and `diff` in some paths) optionally refreshes index stat info,
which briefly takes `.git/index.lock`. Concurrent agent sessions polling
status raced a 1,873-file GitHub Desktop commit at 2026-10-03 17:09 KST and
the commit failed with "A lock file already exists". `--no-optional-locks`
(or `GIT_OPTIONAL_LOCKS=0`) skips only the optional lock — required write
locks for `add`/`commit` still behave normally.

## Rules

1. Agent shells: first line of a session may set the process-local
   `$env:GIT_OPTIONAL_LOCKS='0'` (PowerShell) — never `setx`, never user or
   system env vars, never `git config --global/--system`.
   Or pass the flag per call: `git --no-optional-locks status|diff|show|log`.
2. New scripts must follow the `scripts/git_doctor.py` `run_git()` pattern —
   the flag goes immediately after the git executable, before `-C` and the
   subcommand. Verify with
   `python -B scripts/git_optional_lock_scan.py --baseline <baseline.json>`;
   the scanner must report zero unprotected read calls in changed files.
3. Seeing `.git/index.lock`: never delete it, never kill `git.exe`, never run
   `git gc`/`prune`/`worktree prune`. Inspect with
   `python -B scripts/git_commit_window.py lock` (size, age, owning git.exe
   PIDs/parents) and retry after 3–5 seconds.
4. While `var/git-commit-window.flag` is open (user is committing), defer git
   writes and bulk git reads. `conditional_local_git.py` enforces this for
   `add`/`commit` with `{"status":"blocked","reason":"user-commit-window-open"}`.
5. Before a user commit: `python -B scripts/git_commit_window.py plan` — it
   buckets worktree paths into SAFE / HELD_BY_LEASE / HOT_RECENT / SENSITIVE /
   LARGE and writes an `uncheck-<ts>.txt` list of paths to leave unchecked in
   GitHub Desktop.

## Pointers

- Scanner: `scripts/git_optional_lock_scan.py` (+ `test_git_optional_lock_scan.py`)
- Commit window: `scripts/git_commit_window.py` (+ `test_git_commit_window.py`)
- Flag file: `var/git-commit-window.flag` (JSON: openedAt, ttlMinutes, by)
- Reports: `var/codex-assist-git-lock/commit-window-*.md|json`, `uncheck-*.txt`
- Skill pointer: `.agents/skills/demo1-git-commit-window/SKILL.md`
