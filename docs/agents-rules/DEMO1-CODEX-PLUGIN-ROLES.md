<!-- moved-from: AGENTS.md L115-L120 sha256=a4577d74fe1fd6cc0d7bdab86e4ffe3ab1e02b591118416e934a841173e5506b movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-CODEX-PLUGIN-ROLES -->
## Codex plugin roles (per work type)
- Every Codex task (goal files and PASTE briefs included) auto-applies `$demo1-codex-plugin-roles` v2 (`.agents/skills/demo1-codex-plugin-roles/SKILL.md`) with no paste step: enable only the lanes the work type needs — never all plugins, unknown plugins stay off; the matrix lives there, not here or in chat pastes.
- Every final report must carry the `외부 API:` line (`없음` when empty) and a `PLUGIN_USAGE:` block — checked by `python -B scripts/codex_plugin_usage_lint.py --report <file>`; footer `docs/operations/codex-plugin-roles-footer.txt`. Emphasis shortcut (optional): `agent-prompts/codex-plugin-roles-shortcut.md`. Catalog: `EXTERNAL_SKILLS.md`; Browser/Computer/Supabase lanes: `$demo1-demand-driven-external-proof`.
- No mention line needed: enabled plugins' skills + `codex_apps` connectors load each new session (68-rollout check 2026-10-03); a lane not exposed = report `UNAVAILABLE(미노출)` and use the fallback lane, never ask for a mention.
<!-- END DEMO1-CODEX-PLUGIN-ROLES -->
