---
name: safe-source-edit
description: "Use before non-trivial source patches: three-way preflight, preserve intent"
triggers:
  - user
  - model
redirect: demo1-source-edit-three-way-preflight
deprecated: true
---

# safe-source-edit

## Source of truth (alias)
Read and follow `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md`. Edit the SSOT, not this alias.

## Hard stops
- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
