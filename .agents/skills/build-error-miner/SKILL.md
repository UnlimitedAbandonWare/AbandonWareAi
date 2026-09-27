---
name: build_error_miner
description: Use when classifying build or boot logs through the demo-1 MCP control tower
---

# Build Error Miner

Use this task skill to classify a build log into stable failure classes without exposing raw logs.

Run through the launcher:

```powershell
@{
  nodeRole = "desktop"
  log_path = "build-logs\latest.log"
  audit_log = "logs\awx-mcp-audit.ndjson"
} | ConvertTo-Json -Depth 20 -Compress |
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox.ps1 -Tool build_error_miner
```

Rules:

- Return class counts and a primary class only.
- Do not paste full build logs into reports.
- Use `evidence_needed` when the log path is missing.
- Retry only once after a specific patch and only when the blocker class is unchanged.

For AI-assisted debugging, follow the existing
`../demo1-debugging-with-two-tools/SKILL.md` observation/verification contract
and run `tools/ai_debug_assist.py` from the confirmed root with an explicit
log, exact source allowlist, and new report path. It verifies the existing
owned STDIO MCP with known and missing inputs before using classification;
failed verification falls back to the local miner. It records the actual
log-content SHA-256 because the legacy MCP `logHash` hashes a path.

External AI is optional. The current Codex can supply a hash-bound candidate
using `--proposal`; model-proposed tests remain text until the agent selects
one focused verifier. `status=ok` or a valid file/line reference is not a
verified cause. Never claim a live model call for a supplied candidate.
