---
name: archive.restore
description: Use when restoring files from an archive root through the demo-1 MCP control tower
---

# Archive Restore

Use this task skill only after archive evidence has been reviewed. Mac mini and Notebook restore into local temp/worktree targets; Desktop decides any final canonical-source application.

Run through the launcher:

```powershell
@{
  nodeRole = "macmini"
  mode = "restore"
  archive_root = "BackupsXS"
  canonical_root = "C:\AbandonWare\demo-1\demo-1\src"
  glob = "snapshots/*.java"
  target_dir = "C:\awx-macmini-restore"
  audit_log = "logs\awx-mcp-audit.ndjson"
  verify_log = "logs\awx-mcp-restore-verify.ndjson"
} | ConvertTo-Json -Depth 20 -Compress |
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox.ps1 -Tool archive.restore
```

Rules:

- Required input: `mode=restore`, `glob`, `target_dir`, and `audit_log`.
- Prefer a review call before restore when scope is not exact.
- Treat `preReview.performed=true` and `preReview.candidateCount` as the restore-before-apply evidence.
- Record and verify post-restore checksums; restored rows must include `sha256` and `checksumVerified=true`.
- Prefer `verify_log` for append-only restore evidence; it records pre-review and checksum rows without file contents.
- Audit rows may contain only request/session/node/tool/hash/count/timing/decision/failReason.
- Do not restore directly into Desktop canonical source from Mac mini or Notebook.
- For Mac mini/Notebook, pass `canonical_root` when known; attempts to restore under it must return `decision=restore_target_blocked` and `failReason=smb-conflict-risk`.
