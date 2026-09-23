---
name: source-inspection
description: "Use when starting investigation: lock Project Root to this workspace"
triggers:
  - user
  - model
redirect: demo1-project-root
deprecated: true
---

# source-inspection

## Source of truth (alias)
Read and follow `.agents/skills/demo1-project-root/SKILL.md`. Edit the SSOT, not this alias.

## Hard stops
- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
