---
name: demo1-git-ship
description: Use when the user explicitly asks to commit/push the demo-1 tree in chat ("커밋해줘"/"푸시해줘") — Git-Ship.bat drives the safe status/junk/scan/commit/push/verify lane; never for agent-owned path commits (agent_git_vibe_commit.py) or anything without --apply + approval env.
---

# demo1-git-ship

SSOT: `docs/agents-rules/demo1-git-ship.md` — read it before running.

```
Git-Ship.bat status          # branch/upstream/staged/index.lock
Git-Ship.bat junk            # junk candidates (add --apply to unstage)
Git-Ship.bat scan --staged   # secret shapes -> real/fake/word
Git-Ship.bat ship            # dry-run plan; first line "DRY-RUN: ..."
Git-Ship.bat ship --apply --message "m"   # needs AWX_PUBLISH_APPROVED=1 to push
```

Dry-run by default. Push is new-branch only (never main/master, never
force). index.lock is waited on, never deleted.
