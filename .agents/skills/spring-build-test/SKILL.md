---
name: spring-build-test
description: "Use when choosing how to build/test demo-1: detect existing"
triggers:
  - user
  - model
redirect: demo1-toolchain-auto-select
deprecated: true
---

# spring-build-test

## Source of truth (alias)
Read and follow `.agents/skills/demo1-toolchain-auto-select/SKILL.md`. Edit the SSOT, not this alias.

## Hard stops
- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
