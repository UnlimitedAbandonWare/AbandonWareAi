---
name: regression-check
description: "Use when docs/skills/examples may disagree with live API/Ollama contracts - refresh"
triggers:
  - user
  - model
redirect: demo1-api-spec-drift-guard
deprecated: true
---

# regression-check

## Source of truth (alias)
Read and follow `.agents/skills/demo1-api-spec-drift-guard/SKILL.md`. Edit the SSOT, not this alias.

## Hard stops
- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
