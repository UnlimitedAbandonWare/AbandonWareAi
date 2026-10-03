# Registry field reference — configs/agent-paths.yaml

Schema per entry (mapping under the top-level `paths:` map):

| field | meaning |
|---|---|
| `path` | repo-root-relative path (`/` separators). Absolute/`%USERPROFILE%` allowed only for keys whose target lives outside the checkout (`git.exe`, `user.*`, `python.venv`, `rescue.root`, `worktree.*`). |
| `env` | environment variable that overrides the registry value when set. Empty = none. |
| `status` | `FROZEN` / `ALIASED` / `MOVE-SAFE` / `STABLE` — see SSOT doc. |
| `owner` | owning subsystem/agent lane (informational). |
| `consumers` | up to 5 representative consumer paths (informational). |
| `old_paths` | previous locations still accepted as fallback; resolver logs `[AWX][path-alias]` when one is used. |
| `moved_at` | KST timestamp of last physical move, else `null`. |

Resolution order (`awx_paths.resolve`): env var → registry `path` → first
existing `old_paths` entry → error. `repo.root` is derived from the resolver
file location, never from a literal.

## Key table (2026-10-03)

| key | status | path | env |
|---|---|---|---|
| repo.root | FROZEN | . | AWX_ROOT |
| scripts.dir | STABLE | scripts | AWX_PATH_SCRIPTS_DIR |
| configs.dir | STABLE | configs | AWX_PATH_CONFIGS_DIR |
| docs.agents_rules | STABLE | docs/agents-rules | AWX_PATH_DOCS_AGENTS_RULES |
| docs.diagnostics | STABLE | docs/diagnostics | AWX_PATH_DOCS_DIAGNOSTICS |
| agent_prompts.dir | STABLE | agent-prompts | AWX_PATH_AGENT_PROMPTS |
| handoff.root | FROZEN | data/agent-handoff | AWX_PATH_HANDOFF_ROOT |
| journal.base | FROZEN | data/agent-handoff/codex-autonomy | AWX_PATH_JOURNAL_BASE |
| state.chatgpt_oauth | FROZEN | data/agent-handoff/chatgpt-oauth | CHATGPT_OAUTH_MODELS |
| state.interview_tunnel | FROZEN | data/agent-handoff/interview-tunnel | DEMO_PUBLIC_URL_FILE |
| state.codex_handoff | FROZEN | data/agent-handoff/codex | UAW_AUTOLEARN_AGENT_HANDOFF_ROOT |
| pathmoves.log | FROZEN | data/agent-handoff/_path-moves/moved.jsonl | AWX_PATH_MOVED_LOG |
| device.resources | STABLE | data/device-resources | AWX_PATH_DEVICE_RESOURCES |
| patch_drop.root | FROZEN | __patch_drop__ | AWX_PATH_PATCH_DROP |
| lease.locks | FROZEN | __patch_drop__/source-edit-locks | AWX_PATH_LEASE_LOCKS |
| lease.events | FROZEN | __patch_drop__/source-edit-events | AWX_PATH_LEASE_EVENTS |
| lease.heartbeats | FROZEN | __patch_drop__/source-edit-heartbeats | AWX_PATH_LEASE_HEARTBEATS |
| lease.scopes | FROZEN | __patch_drop__/source-edit-scopes | AWX_PATH_LEASE_SCOPES |
| lease.quarantine | FROZEN | __patch_drop__/source-edit-quarantine | AWX_PATH_LEASE_QUARANTINE |
| hooks.dir | FROZEN | .codex/hooks | AWX_PATH_HOOKS_DIR |
| codex.dir | STABLE | .codex | AWX_PATH_CODEX_DIR |
| config.project_resources | FROZEN | config/project-resources.json | AWX_PATH_PROJECT_RESOURCES |
| var.root | STABLE | var | AWX_PATH_VAR_ROOT |
| var.debug | STABLE | var/debug | AWX_PATH_VAR_DEBUG |
| var.rag_launcher | FROZEN | var/rag-launcher | AWX_PATH_RAG_LAUNCHER |
| var.codex_runtime | FROZEN | var/codex-runtime | LOCAL_LLM_LOG_DIR |
| db.meta_display | FROZEN | var/meta-display-db/lmsdb | AWX_PATH_META_DISPLAY_DB |
| port_lease.dir | FROZEN | var/agent-port-lease | AWX_PATH_PORT_LEASE |
| assist.output_root | ALIASED | var/codex-assist | AWX_PATH_ASSIST_OUTPUT |
| main.resources | STABLE | main/resources | AWX_PATH_MAIN_RESOURCES |
| main.java | STABLE | main/java | AWX_PATH_MAIN_JAVA |
| frontend.dir | STABLE | frontend | AWX_PATH_FRONTEND |
| runtime_state.proposed | ALIASED | data/runtime-state | AWX_PATH_RUNTIME_STATE |
| rescue.root | STABLE | C:/AbandonWare/_rescue | AWX_PATH_RESCUE_ROOT |
| worktree.macmini | STABLE | C:/AbandonWare/worktrees/awx-macmini | AWX_PATH_WORKTREE_MACMINI |
| worktree.notebook | STABLE | C:/AbandonWare/worktrees/awx-notebook | AWX_PATH_WORKTREE_NOTEBOOK |
| git.exe | FROZEN | F:/git/cmd/git.exe | AWX_GIT_EXE |
| user.home | FROZEN | %USERPROFILE% | USERPROFILE |
| user.downloads | FROZEN | %USERPROFILE%/Downloads | AWX_PATH_USER_DOWNLOADS |
| python.venv | FROZEN | %USERPROFILE%/AppData/Local/hermes/hermes-agent/venv/Scripts/python.exe | AWX_PYTHON_EXE |
