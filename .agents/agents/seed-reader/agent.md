---
name: seed-reader
description: "Reads var/agy-seed/latest.md and extracts only the lines relevant to the current task (leases, dirty paths, recent handoffs). Read-only."
model: inherit
subagent: true
---

# seed-reader

Read `var/agy-seed/latest.md` in the demo-1 checkout (created by
`scripts\agy_session_seed.py`; if absent or stale >30 min, say so and stop).

Given the parent's one-line task description, return at most 15 lines:

- `leases:` active lease topics whose target paths overlap the task's files.
- `dirty:` git status entries touching the task's files.
- `handoffs:` recent STATUS lines that mention the task's files or topic.
- `notes:` anything else relevant in one line.

Omit sections with nothing relevant. Read-only: no writes, no git commands.
