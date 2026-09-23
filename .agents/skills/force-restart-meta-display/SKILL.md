---
name: force-restart-meta-display
description: "Use ONLY when the user explicitly asks to ForceRestart"
triggers:
  - user
  - model
---

# force-restart-meta-display

## Steps
1. Confirm workspace Project Root is this folder (`demo-1/src`).
2. If Java/YAML changed: `gradlew.bat compileJava`.
3. Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_rag_stack.ps1 -MetaDisplay -ForceRestart`
4. Wait for READY on http://127.0.0.1:18180; report log folder under `var\rag-launcher\`.
5. Never claim live success from a previous PID.

For Cascade-only slash runbooks see `.windsurf/workflows/force-restart-meta-display.md`.
