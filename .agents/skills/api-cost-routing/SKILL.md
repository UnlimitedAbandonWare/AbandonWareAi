---
name: api-cost-routing
description: "Use when changing search/RAG/ASR/embedding/LLM routing"
triggers:
  - user
  - model
redirect: demo1-api-routing-inventory
deprecated: true
---

# api-cost-routing

## Source of truth (alias)
Read and follow `.agents/skills/demo1-api-routing-inventory/SKILL.md`. Edit the SSOT, not this alias.

## Hard stops
- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
