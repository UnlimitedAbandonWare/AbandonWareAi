<!-- moved-from: AGENTS.md L73-L77 sha256=e257361bf8be2380fb39018b15f77920b909574e90e9be6f8eb25afc49bbb207 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-GOAL-SWITCH -->
## Goal-Switch Barrier
- Objective rotation or a new purpose opening while old `in_progress` journals/leases remain -> `$demo1-goal-switch-barrier` (`.agents/skills/demo1-goal-switch-barrier/SKILL.md`): `python -B scripts/demo1_goal_switch_barrier.py check|switch --root . --agent <name>` closes owned stale journals (superseded/abandoned), releases owned claims/leases via `source_edit_session.ps1`, and echoes a fresh `demo1_vibe_skill_router.py resolve` - foreign/recent/active-lease journals are never touched; `reject-complete` blocks instructional text mistaken for acceptance.
- Wired into `objective-executor` step 2 and `demo1-goal-complete-stop` step 4; journals and leases stay in `work_journal.py` + `__patch_drop__/source_edit_session.ps1` - the barrier composes them, it is not a new lock layer.
<!-- END DEMO1-GOAL-SWITCH -->
