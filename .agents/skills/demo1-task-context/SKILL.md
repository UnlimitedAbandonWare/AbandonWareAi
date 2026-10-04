---
name: demo1-task-context
description: Build or inspect offline task-scoped handoff context and recorded file-hash freshness.
---

# Task context
Run from the verified project root; Python standard library only.
Use an exact taskId; agent names and newest directories do not select a task.

- Preview: `python -B scripts/task_context.py build --task ID --dry-run --json`
- Publish: `python -B scripts/task_context.py build --task ID`
- Read: `python -B scripts/task_context.py show --task ID`
- Check: `python -B scripts/task_context.py verify --task ID --json`
- List: `python -B scripts/task_context.py list --since-hours 24 --limit 10`
- Override project: `--root PATH` before or after the command.
- Journal/handoff locations use `configs/agent-paths.yaml` and its declared env overrides.
- External task checkpoint directory: `build --checkpoint-root PATH` or `AWX_TASK_CONTEXT_CHECKPOINT_ROOT`.
- External cooperative store: `build --coop-store PATH`.

Only derived revisions and the current pointer are written below the selected task's `context/`.
Keep reported text, observed exit, receipt tier and current file applicability separate.
A checkpoint's caller-observed tier is preserved; an old PASS can be STALE_PASS.
Verify reads revision artifacts and recorded file hashes, never the journal.
Verify exits: FRESH=0, STALE=10, CORRUPT=20; FRESH is not a task-completion verdict.
Missing show exits 4; a concurrent build exits 3 (BUSY).
Never remove a lock based on age; establish its PID is dead before manual recovery.
Caps: journal 4 MiB, latest events 2000, combined receipts/checkpoints 200, JSON 64 KiB, Markdown 12 KiB.
Truncation, missing/unreadable evidence and unobserved lease/dirty state remain explicit.
Do not collect dialogue, prompts, secrets, environment values, or absolute user paths.

