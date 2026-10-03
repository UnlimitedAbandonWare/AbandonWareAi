---
trigger: always_on
description: ChatGPT OAuth runtime rails. Fixed wire flags, fail-closed billing, token isolation. Product patch stays with Codex.
---

# ChatGPT OAuth runtime rails

Contract `DEMO1-CHATGPT-OAUTH-RUNTIME-RAILS-20260930`. Report: `data/agent-handoff/clean-chatgpt-oauth-redteam-20260930/REVIEW_GATE_REPORT.md`.
- OAuth `/v1/responses` calls always send `store:false` + `stream:true` + `Accept: text/event-stream`. `temperature`, `top_p`, `max_output_tokens`, `background`, `previous_response_id` never go on the wire.
- Fail-closed: an OAuth failure (incl. `subscription_sharing_*`) never falls back to an API-key or non-OAuth route. No silent paid-key retry. Fallback needs an explicit user-approved `allow-paid-fallback` flag.
- `ext_agent_host_id` is a URN (`urn:uuid:` / `urn:ietf:params:oauth:jwk-thumbprint:` / `did:key:`); redirect stays `http://127.0.0.1:1455/auth/callback`.
- Tokens live only in `.secrets/chatgpt_oauth_credentials.json`. Never in git, logs, TraceStore, console, or exception text. `python -B scripts/scan_chatgpt_oauth_secrets.py` and `python -B scripts/lint_chatgpt_oauth_contract.py` gate this.
- The 62,500-credit figure is a user-reported allocation (confirmed 2026-10-03), never an observed balance: keep it as the `codex_credits` input in `configs/agent-api-spend-guard.yaml`, and never treat OAuth calls as $0.
- Clean owns the scanner, linter, `ChatGptOAuthRedTeamContractTest`, and this rail. Product `main/**` stays Codex-owned; no commit, no push.
