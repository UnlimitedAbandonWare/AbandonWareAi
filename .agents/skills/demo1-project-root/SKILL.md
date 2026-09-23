---
name: demo1-project-root
description: Use at task start to lock Project Root to this demo-1 workspace
---

# demo1-project-root

## Project Root
`C:\AbandonWare\demo-1\demo-1\src`

## Rules
- Treat the path above as the default Project Root for shell cwd, greps, patches, Gradle, and `Start-RAG.bat`.
- Do not pick `C:\AbandonWare`, `C:\AbandonWare\demo-1`, or `...\demo-1\demo-1` as the code root unless the user explicitly redirects.
- Relative paths in AGENTS.md and `.agents/skills` resolve from this root.
- AGENTS.md SSOT section: `DEMO1-PROJECT-ROOT`.
