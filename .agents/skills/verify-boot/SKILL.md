---
name: verify_boot
description: Use when deriving or running Desktop/Mac mini/Notebook boot
---

# Verify Boot

Use this task skill to obtain role-local verification commands. The tool returns commands; it does not itself prove Desktop success.

Run through the launcher:

```powershell
@{
  nodeRole = "desktop"
  root = "C:\AbandonWare\demo-1\demo-1\src"
  audit_log = "logs\awx-mcp-audit.ndjson"
} | ConvertTo-Json -Depth 20 -Compress |
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox.ps1 -Tool verify_boot
```

Rules:

- Desktop must run final Gradle proof from the canonical root.
- Mac mini and Notebook proof is supporting evidence only.
- Use role-local `GRADLE_USER_HOME` and `--project-cache-dir`.
- Do not claim boot or build success until command output proves it.
