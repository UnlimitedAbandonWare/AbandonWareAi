---
name: demo1-test-model-policy
description: /chat이나 API로 RAG·챗봇·모델 응답을 테스트하기 전에 반드시 사용. 화면 기본 모델을 그대로 쓰지 말고 정책으로 모델을 고른다.
---

# demo1 Test Model Policy

Agents testing /chat or the chat API must not fire with the UI default model
(`qwen3.5:9b`, a local Ollama pick). Resolve a model from
`configs/agent-test-model-policy.yaml` first.

## Policy in one line

- **Until 2026-12-30 23:59 KST (api_first):** test on `chatgpt-oauth:*` routes
  (ChatGPT plan credits). All API candidates down → `BLOCKED_API`, stop —
  never slide to local quietly. Local only via explicit `local_fallback`.
- **From 2026-12-31 (dynamic):** purpose + availability + cost decide;
  local lanes become legal candidates again.

## Order of operations

1. `python -B scripts/test_model_policy.py resolve --purpose <quality|regression|smoke|cross_provider|local_fallback|practice_chat|practice_reasoning|practice_compare|auto>`
   — reads `/api/chat/models` live (or `--catalog fixture.json`), honors
   recorded 401/403/429 exclusions, prints `selected` + `alternates`.
   `auto` requires `--prompt-file <f>` (`--has-attachment` optional): classifies
   by keyword/length rules in `autoClassify` (evidence→quality, attachment/
   code/long→practice_reasoning, else practice_chat) and prints `auto→<p>: why`.
2. Select the model in the UI: `require('./browser_model_select.js').selectModel(page, resolved.selected)`
   (handles `chatgpt-oauth:` route ids vs bare `modelId` options).
   Headless/probe path: `scripts/main_chat_target_probe.py --local --model-purpose <p>`.
3. Send the test message.
4. Verify the **observed** model (SSE `modelUsed` / `x-model-used` header /
   trace-dock) equals the selected route:
   `python -B scripts/test_model_policy.py check --selected <id> --observed <id>`
   → `OK` | `SILENT_FALLBACK` | `LOCAL_BEFORE_CUTOFF` | `UNOBSERVED` (no model answered, e.g. send failed — not a fallback). Non-OK ⇒ no PASS.
5. Record every generation: `... record --agent <name> --purpose <p> --model <id> --code <http>`;
   check `budget --run <taskId>` before sending — cap is 25 API generations/run.

## Practice runs (free chat practice, not only RAG verification)

- Browser practice/testing on /chat never uses the UI default model: run
  `node scripts/chat_practice_browser.js --prompts <file>` (default base
  `http://127.0.0.1:18180`; `--driver sync` for a no-browser loopback check;
  `--public` required for a public URL → `[devin-test]` prefix, ≤3 sends).
- With `--prompts` (json array or one-per-line) each question re-resolves via
  `plan`; a model change opens a new conversation (`#newChatBtn`). Per-run cap
  `--max-calls` 10; 401/403/429 → next rank once, never same-model retry.
- Report: `data/agent-handoff/test-model-policy/practice-<ts>.md` — prompt
  summary(40), purpose, selected vs observed, ms, verdict; no answer text.

## Never

- pick `chatgpt-oauth:codex-auto-review` (not a chat model),
- report PASS on a `SILENT_FALLBACK`/`LOCAL_BEFORE_CUTOFF` result,
- retry a 401/403/429 model within the same purpose — move one rank down once,
- treat `reported_grant_credits` as an observed balance (USER_CONFIRM: 6250 vs 62500).

## Files

- Policy SSOT: `configs/agent-test-model-policy.yaml`
- Resolver/ledger: `scripts/test_model_policy.py`, usage log `data/agent-handoff/test-model-policy/usage.jsonl`
- Browser helper: `scripts/browser_model_select.js`; practice runner: `scripts/chat_practice_browser.js`
- User card: `docs/agents/TEST_MODEL_POLICY_KO.md`; brief clause: `docs/agents/TEST_MODEL_BRIEF_CLAUSE.txt`

## Related

- Post-/chat-fix browser matrix (auth models × varied prompts × symptom
  gestures): `.agents/skills/demo1-codex-auth-model-browser-matrix/SKILL.md`
  — runner `scripts/chat_auth_model_matrix_browser.js` (`--dry-run` first).
