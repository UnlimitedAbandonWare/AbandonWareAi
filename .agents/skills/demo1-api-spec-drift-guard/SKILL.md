---
name: demo1-api-spec-drift-guard
description: Use when demo-1 docs/skills disagree with live vendor APIs or Ollama inventory
---

# Demo1 API Spec Drift Guard

Old Markdown, examples, comments, skills, and prior Codex notes often lag real APIs. Treat **live behavior + current vendor docs + `configs/api-routing.yaml`** as newer than narrative docs.

## When

- User says specs changed / Codex used the wrong API shape
- 401/403/404/429, unknown model tag, renamed field, deprecated endpoint
- Display/RAG/LLM task would copy an old snippet without a live check

## Workflow

1. Confirm CWD is the Desktop canonical root.
2. Run:
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\demo1-api-spec-drift-guard\scripts\Refresh-ApiDrift.ps1
   ```
3. Read `references/last-drift-report.md` and `.agents/skills/demo1-api-routing-inventory/references/live-inventory.md`.
4. Open current vendor docs only for the **touched** provider. Prefer field-level diffs over long paste.
5. Update in this order:
   1. `configs/api-routing.yaml`
   2. existing KeyResolver / ProviderGuard / Conversate / embedding / ASR seams
   3. stale comments or skill text last
6. Re-read `$demo1-api-routing-inventory` Do-not list. Never print secrets. Never feed cloud keys into Ollama.
7. Verify with the smallest live probe.

## Auto-adapt rule

If code, comment, skill, or AGENTS prose **disagrees** with the refreshed inventory / vendor contract, **change the code and routing YAML to the live contract** and demote the stale prose in the same task. Do not implement the old doc.

## Pair with

- `$demo1-core-request-router`
- `$demo1-api-routing-inventory`
- `$demo1-local-llm-gpu-gateway`
- `$demo1-rag-platform`