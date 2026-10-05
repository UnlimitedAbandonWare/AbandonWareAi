---
name: demo1-git-commit-window
description: 'Use when the user is about to commit in GitHub Desktop while agent sessions share the worktree — plan safe-to-commit paths, report index.lock/git.exe state, and open/close the user-commit-window flag. SSOT: docs/agents-rules/demo1-git-read-no-lock.md'
---

# demo1-git-commit-window

SSOT: `docs/agents-rules/demo1-git-read-no-lock.md` (read-only git must use
`--no-optional-locks`; `.git/index.lock` is never deleted; `git.exe` is never
killed).

Commands (from Project Root):

```powershell
python -B scripts/git_commit_window.py plan    # SAFE/HELD/HOT/SENSITIVE/LARGE + uncheck list
python -B scripts/git_commit_window.py lock    # index.lock presence/size/age + git.exe PIDs
python -B scripts/git_commit_window.py open    # mark "user committing" (close when done)
python -B scripts/git_commit_window.py close
python -B scripts/git_commit_window.py status  # flag state; expired TTL is reported, not deleted
```
