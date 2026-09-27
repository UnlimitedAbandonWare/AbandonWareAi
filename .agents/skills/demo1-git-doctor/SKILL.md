---
name: demo1-git-doctor
description: Use when this project's Git workflow is blocked by index locks, foreign staging, root mismatch, remote access errors, in-progress operations, or unclear worktree ownership; not for publishing or destructive repair.
---

# demo1-git-doctor

Read-only Git environment diagnosis for this root. It reports what is
blocked, what still works, and who owns the next action — it never repairs,
deletes, or mutates.

## When

- `index.lock`, foreign staged paths, root/worktree mismatch, mass
  deletions, remote access failure, suspected unrelated histories, or
  conflicting policy documents block or confuse Git work.

## Do

```powershell
# full local inventory (read-only; no network)
python -B scripts/git_doctor.py --root .

# machine-readable result for handoff
python -B scripts/git_doctor.py --root . --json

# mark which paths this session owns -> foreign-staging split
python -B scripts/git_doctor.py --root . --owned-path scripts/git_doctor.py

# optional bounded remote probe (ls-remote HEAD only; needs explicit ask)
python -B scripts/git_doctor.py --root . --probe-remote origin
```

- Each issue carries a reason code, `blockedActions`,
  `continuableActions`, and `nextOwner` (`user` / `foreign-session` /
  `agent` / `needs-decision`). A lock blocks index writes; it does **not**
  block reads, file edits, or fixture work — report both halves.
- `prunable`/`offline` worktrees are observations, never auto-pruned —
  SMB/external drives can be temporarily disconnected.
- Absent remote metadata does not block source edits; report it separately.
- An `origin` still pointing at `AbandonWare3` is a stale-remote finding, not
  a defect to repair — report it under the sole-remote policy (sole valid:
  `AbandonWareAi`, `https://github.com/UnlimitedAbandonWare/AbandonWareAi`;
  AGENTS.md `DEMO1-GIT-REMOTE-SOLE`); the doctor never mutates remotes.

## Do not

- Never delete `index.lock`, unstage foreign paths, prune worktrees, or kill
  `git.exe` — doctor output is a handoff map, not a repair license.
- No network access unless `--probe-remote` is explicitly requested; local
  diagnosis never calls fetch/ls-remote implicitly.
- Never print file contents, staged blob diffs, or credential-shaped values.

## Related

- `$demo1-conditional-local-git` — gated write path doctor refers to.
- `$demo1-git-secret-guard` — content/path scans.
- `$scoped-blocker-recovery` — lease/HOLD recovery once the blocker is named.
