---
name: start-rag-reload
description: "Use when Java/Spring/Display sources changed and Start-RAG reload is needed"
triggers:
  - user
  - model
redirect: demo1-dev-reload
deprecated: true
---

# start-rag-reload

## Source of truth (alias)
Read and follow `.agents/skills/demo1-dev-reload/SKILL.md`. Edit the SSOT, not this alias.

## Hard stops
- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
