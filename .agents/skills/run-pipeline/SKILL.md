---
name: run_pipeline
description: Use when probing run_pipeline, verify_boot, build_error_miner via demo-1 MCP tower
---

# Run Pipeline

Use this task skill to probe pipeline script availability, return exact commands, and include the read-only MCP control-tower plan JSON as `pipelinePlan`. It is a read-only command-shape and plan-probe task unless the user explicitly asks to execute returned producer/Desktop commands. In this checkout, the MCP control-tower pipeline is exposed through `scripts/awx_mcp_control_tower_pipeline.py` when the legacy `orchestration/run_pipeline.sh` is absent.

Run through the launcher:

```powershell
@{
  nodeRole = "desktop"
  root = "C:\AbandonWare\demo-1\demo-1\src"
  audit_log = "logs\awx-mcp-audit.ndjson"
} | ConvertTo-Json -Depth 20 -Compress |
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox.ps1 -Tool run_pipeline
```

Rules:

- Missing scripts are `evidence_needed`, not proof of source failure.
- Prefer `python scripts/awx_mcp_control_tower_pipeline.py --root . --plan-only` for the MCP control-tower proof plan; `run_pipeline` should surface that output as `pipelinePlan` while keeping Desktop final proof pending.
- Prefer Desktop sequential proof for boot/smoke.
- Keep Mac mini and Notebook runs as supporting evidence.
- Do not run multiple boot processes on the same shared cache or port set.
