---
description: Skill/rules metadata and family edits (loads when touching skills, Cline rules, or AGENTS.md).
paths:
  - ".agents/skills/**"
  - ".clinerules/**"
  - ".cline/skills/**"
  - "AGENTS.md"
---

# Skill-family edits — keep discovery consistent

This rule only activates when a request or open file touches skill/rules files (see `paths`).

- After skill or prompt-family edits: read `.agents/skills/demo1-skill-family-postprocessor/SKILL.md` and run its post-edit checks.
- Cline discovery requires frontmatter `name:` to exactly match the directory name. Some canonical `.agents/skills` names are MCP tool ids (`verify_boot`, `run_pipeline`, `build_error_miner`, `archive.restore`, `archive.search`) referenced by `agents/openai.yaml`, catalogs, and `scripts/awx_mcp_toolbox*.py` — do not rename them; `.cline/skills/` holds the minimal connectors instead.
- Keep the canonical skill body in `.agents/skills/`; connectors point at it, never copy it.
- Preserve `BEGIN/END` markers, existing section anchors, and pointers in `AGENTS.md`.
