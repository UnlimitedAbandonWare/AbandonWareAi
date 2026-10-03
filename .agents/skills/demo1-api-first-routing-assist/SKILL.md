---
name: demo1-api-first-routing-assist
description: >-
  Use when API-first chat routing, model-name-guess removal, non-waiting Ollama
  fallback, or plan9 fallback rules need assist tooling or verification beside
  the agent that owns the product patch. Prepares read-only scanners, order
  previews, loopback mocks, a 25-call budget ledger, and dry-run verifiers.
  Product Java/YAML/JS and tests stay owned by the patching session. A tool
  PASS is not an application completion.
---

# demo1-api-first-routing-assist

Assist rail for the Codex Plan9 task (registered-route API-first chat
generation: cloud A -> cloud B -> non-waiting local). This skill does not patch
`main/java`, `main/resources`, `src/test`, or `chat.js`.

## When

Use it when the ask is to prepare or re-check assist material for:

- routing order where registered cloud routes come before local Ollama
- removing provider/endpoint guesses made from model-name strings
- `chatgpt-oauth:` routes that must never be auto-selected
- bounded live verification with a hard external API call budget

Skip it for product edits. Those belong to the session holding the source lease.

## Outputs and scripts

Run from `<repo>`. Outputs live under
`var/codex-assist-plan9-20261002/` (re-runs add `-001`).

```powershell
# G1 where does code still guess provider/endpoint from a model name
python -B scripts/model_name_guess_scan.py --root main --format md --out var/codex-assist-plan9-20261002/name-guess-baseline.md
# regression guard: new routing_decision hits vs baseline -> exit 2
python -B scripts/model_name_guess_scan.py --root main --baseline var/codex-assist-plan9-20261002/name-guess-baseline.json

# G2 legacy vs api-first effective order (assumes LLMROUTER_API_FIRST=true), OAuth excluded
python -B scripts/api_first_order_preview.py --json

# G3 loopback mock matrix (11 scenarios: ok/timeout/503/429x3/401/403/400/partial/slow)
python -B scripts/api_first_mock_matrix.py samples --out var/codex-assist-plan9-20261002/mock-samples

# G5 API call ledger: cap 25; 401/403/429 -> STOP_AND_ROOT_CAUSE (exit 4)
python -B scripts/api_call_budget.py init --ledger var/codex-assist-plan9-20261002/API_CALLS.md --cap 25
python -B scripts/api_call_budget.py record --ledger var/codex-assist-plan9-20261002/API_CALLS.md --route <id> --http <code> --result ok
python -B scripts/api_call_budget.py check --ledger var/codex-assist-plan9-20261002/API_CALLS.md

# G6/G7 dry-run wrappers (never send/never gradle without -Live/-RunGradle)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/api_first_live_check.ps1 -Scenario All -DryRun
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/codex_plan9_verify.ps1 -Wp All -DryRun
```

Each Python tool has a sibling `scripts/test_<name>.py` (stdlib unittest,
synthetic fixtures only). `codex_plan9_verify.ps1` has no full-suite and no
clean switch; `-RunGradle` is Codex's step while no other agent holds Gradle.

## Related existing pieces (reuse, do not duplicate)

- `scripts/test_model_policy.py` (+ `chat_practice_browser.js`,
  `browser_model_select.js`, `chat_rag_golden_browser.js`) - policy-resolved
  model choice + loopback sends; may be under the `devin-test-model-api-policy`
  lease - read/run only.
- `scripts/gemini_mock_gateway.py`, `scripts/jev_mock_gateway.py` - earlier
  loopback mock patterns the matrix mirrors.
- `scripts/codex_mock_sse.ps1` - PID-file mock lifecycle pattern.
- `.codex/agents/assist_routing_mapper.toml` etc. - Codex-side assist
  subagents; this rail documents, never edits them.
- Staged JUnit5 material for Codex's 16 tests:
  `var/codex-assist-plan9-20261002/test-staging/` + `extend-map.md`.

## Do not

- Edit product source, product tests, `chat.js`, existing scripts/skills/rules.
- Call OpenAI/Gemini/Groq/ChatGPT OAuth or any external API; public-site sends.
- Read `.env`, `.secrets`, `apikey.txt`, `application-secrets.yml`; print tokens.
- Judge provider by model-name strings - only config `provider` / catalog
  `provider` fields are authoritative (that is the whole point of the task).
- Run Gradle or the full suite; `git add/commit/push`; restart the server.
- Force-release another session's lease; re-check `work_journal.py` +
  `source_edit_session.ps1 status` first when a target path is claimed.

## Tool PASS is not app done

Scanner baselines, preview tables, mock samples, budget exit codes, and
dry-run wrappers prove the tooling only. The routing change is proven by
Codex's focused tests plus the V1-V5 live checks inside the 25-call cap.
