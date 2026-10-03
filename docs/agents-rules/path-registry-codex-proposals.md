# Path-registry proposals for Codex lanes (proposal-only, 2026-10-03)

Scope ② did not allow editing Java/YAML/BAT/hooks this session. Each row is a
file:line, the current literal/default, and the registry key it could resolve
through (Java keeps env-var style indirection so behavior is unchanged).

## Product-side literals → registry keys

| file:line | current value | proposed key / env |
|---|---|---|
| main/java/com/example/lms/llm/ChatGptOAuthRegistration.java:180 + main/resources/application-meta-display.yml:250 | `${CHATGPT_OAUTH_MODELS:data/agent-handoff/chatgpt-oauth/models.json}` | `state.chatgpt_oauth` (env already `CHATGPT_OAUTH_MODELS`) |
| main/java/com/example/lms/api/InterviewDemoPublicAddress.java:14 | `${demo.public-url-file:data/agent-handoff/interview-tunnel/state.json}` | `state.interview_tunnel` (env `DEMO_PUBLIC_URL_FILE`) |
| main/resources/application.yml:806-807 | `${UAW_AUTOLEARN_AGENT_HANDOFF_ROOT:data/agent-handoff/codex}` + `…/accepted.jsonl` | `state.codex_handoff` (env already present) |
| main/java/com/example/lms/tools/DbEvidenceScanTool.java:215 | counts files under `data/agent-handoff` | `handoff.root` |
| main/java/com/example/lms/guard (VectorPoisonGuard.java:101) | literal `"/data/agent-handoff/"` pattern | document pattern ↔ `handoff.root` drift guard |
| main/resources/application-meta-display.yml:7 | `./var/meta-display-db/lmsdb` | `db.meta_display` |
| main/resources/application-llm.yaml:163 | `${LOCAL_LLM_LOG_DIR:var/codex-runtime}` | `var.codex_runtime` (env already `LOCAL_LLM_LOG_DIR`) |

## Script/toolchain anchors

| file:line | current value | proposed |
|---|---|---|
| __patch_drop__/source_edit_session.ps1:109 | `Join-Path $ProjectRoot '__patch_drop__\source-edit-locks'` | `Resolve-AwxPath -Key lease.locks` (value unchanged; signal path stays FROZEN) |
| Start-Grok-CLI.bat / Start-Codex-CLI.bat / Start-Agy-CLI.bat | `F:\git\cmd\git.exe` literals | `AWX_GIT_EXE` env or `awx_paths.py where git.exe` wrapper |
| .codex/hooks.json + .codex/hooks/* | repo-root literals in hook commands | registry key `hooks.dir` + note in hook docs |

## Relocation proposal (not executed)

- Product-read state files (`state.chatgpt_oauth`, `state.interview_tunnel`,
  `state.codex_handoff`) could move to `data/runtime-state/` — registered as
  `runtime_state.proposed` (ALIASED). Requires product change first; leave as
  proposal.
- `var/codex-assist-*` flat dirs → `var/codex-assist/<topic>/` under
  `assist.output_root` once the directories age past 72h with no live session;
  keep junction aliases for 14 days.
- `data/agent-handoff/<agent>/<topic>-<8hex>` layout: keep as ALIASED proposal;
  hundreds of globs depend on the current shape.
