---
name: archive.search
description: Use when searching BackupsXS/index.jsonl through the demo-1 MCP control tower
---

# Archive Search

Use this task skill for read-only archive lookup. It must not restore files or edit source.

Run through the launcher:

```powershell
@{
  nodeRole = "macmini"
  q = "<query>"
  filters = @{}
  top_k = 5
  index_path = "BackupsXS\index.jsonl"
  audit_log = "logs\awx-mcp-audit.ndjson"
} | ConvertTo-Json -Depth 20 -Compress |
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox.ps1 -Tool archive.search
```

Rules:

- Use `q`, `filters`, and `top_k`.
- Resolve the index as `index_path` first, then `ARCHIVE_INDEX`, then `NAS_ARCHIVE_ROOT\index.jsonl`, then `BackupsXS\index.jsonl`.
- Require at least two query passes.
- If 0 results remain, report `evidence_needed` and expanded queries.
- Return path/title/hash evidence only, not full archive contents.
- Keep Desktop final source changes out of this task.
