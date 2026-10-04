<!-- moved-from: AGENTS.md L321-L325 sha256=313fb5dabee80a1cadf9933d80f19ee952a26997d929094e18d7702adff3e636 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-COMPLETION-CLEANUP -->
### Automatic completion and cleanup
- At the final verified boundary of a patch or report-only task, automatically use `.agents/skills/demo1-completed-directive-cleanup/SKILL.md`. The 2026-09-15 authorization covers stopping completed work and deleting its proven surplus artifacts; do not ask for another routine cleanup confirmation.
- Bind the exact whole-task evidence using `awx.completed-task-cleanup.v1`; neither file age nor an isolated green test permits cleanup — never select another task or patch by newest timestamp. For checkpoint work, prepare `task-cleanup-request.json` in the exact cycle before the final successful `codex_work_checkpoint.py finish`; preserve source, final patches, reports, verification evidence, receipts, recovery files — no recursive or queue-wide sweep. A cleanup failure never requeues a completed patch.
<!-- END DEMO1-COMPLETION-CLEANUP -->
