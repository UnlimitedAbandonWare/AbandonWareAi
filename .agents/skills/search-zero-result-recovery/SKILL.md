---
name: search-zero-result-recovery
description: "Use when search/RAG returns zero hits and recovery/observability steps are needed."
triggers:
  - user
  - model
redirect: demo1-rag-resilience-observability
deprecated: true
---

# search-zero-result-recovery

## Source of truth (alias)
Read and follow `.agents/skills/demo1-rag-resilience-observability/SKILL.md`. Edit the SSOT, not this alias.

## Hard stops
- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
