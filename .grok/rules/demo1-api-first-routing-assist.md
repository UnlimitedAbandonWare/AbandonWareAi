# API-first routing assist pointer

SSOT: `.agents/skills/demo1-api-first-routing-assist/SKILL.md`.
Assist rail for Codex Plan9 API-first routing (2026-10-02). Devin/Grok prepare scanners, order preview, loopback mocks, budget ledger, dry-run verifiers. Product Java/YAML/JS and tests stay Codex-owned.
Outputs: `var/codex-assist-plan9-20261002/`. New scripts: `scripts/model_name_guess_scan.py`, `scripts/api_first_order_preview.py`, `scripts/api_first_mock_matrix.py`, `scripts/api_call_budget.py`, `scripts/api_first_live_check.ps1`, `scripts/codex_plan9_verify.ps1`.
Tool PASS is not application completion. Provider judgment comes from config/catalog `provider`, never model-name strings. 0 external API calls on this rail; 401/403/429 mean STOP_AND_ROOT_CAUSE.
