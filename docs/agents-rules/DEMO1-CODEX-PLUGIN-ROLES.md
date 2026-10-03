<!-- moved-from: AGENTS.md L111-L115 sha256=9b2f83350955a1b72905a79950281059d141c063f803fd308c4c3054b34e8cf8 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-CODEX-PLUGIN-ROLES -->
## Codex plugin roles (per work type)
- Every Codex task (goal files and PASTE briefs included) auto-applies `$demo1-codex-plugin-roles` v2 (`.agents/skills/demo1-codex-plugin-roles/SKILL.md`) with no paste step: enable only the lanes the work type needs — never all plugins, unknown plugins stay off; the matrix lives there, not here or in chat pastes.
- Every final report must carry the `외부 API:` line (`없음` when empty) and a `PLUGIN_USAGE:` block — checked by `python -B scripts/codex_plugin_usage_lint.py --report <file>`; footer `docs/operations/codex-plugin-roles-footer.txt`. Emphasis shortcut (optional): `agent-prompts/codex-plugin-roles-shortcut.md`. Catalog: `EXTERNAL_SKILLS.md`; Browser/Computer/Supabase lanes: `$demo1-demand-driven-external-proof`.
<!-- END DEMO1-CODEX-PLUGIN-ROLES -->
