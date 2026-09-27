---
name: compile-verify-smoke
description: "Use ONLY when the user explicitly asks to compile or run smoke/verify scripts"
triggers:
  - user
  - model
---

# compile-verify-smoke

## Steps
1. Stay in Project Root.
2. Prefer `gradlew.bat compileJava` and existing in-repo `smoke_*` / `verify_*` / frontend npm scripts.
3. Report exact exit codes; on failure stop at the first actionable error.

For Cascade-only slash runbooks see `.windsurf/workflows/compile-verify-smoke.md`.
