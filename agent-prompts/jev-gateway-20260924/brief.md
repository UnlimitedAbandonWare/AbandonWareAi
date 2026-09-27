# Devin brief — Jev (typesafe-ai/jev) API smoke → then wire into demo-1

- Project Root: `C:\AbandonWare\demo-1\demo-1\src`
- Model: `typesafe-ai/jev` via Vercel AI Gateway (Free Tier listed Free/Free, 32K state)
- Credential (name only): `AI_GATEWAY_API_KEY` (already present on host). Never print value.
- NO commit/push/add -A. NO secrets in logs/report. NO force lease. Preserve foreign staging.
- Prefer min-diff. Free/cheap-first. Do not replace chat/Ollama with Jev.

## Goal

1. Prove Jev evaluate API works with existing `AI_GATEWAY_API_KEY`.
2. Only if smoke passes: transplant into existing demo-1 as a **decision layer**
   (route/gate), not as the answer-generating LLM.

## Non-goals

- Swap gemma/qwen/chat/cueHint prose generation to Jev
- OpenRouter env / TypeSafe direct key (use Vercel Gateway path)
- Mass skill rewrite, SMB, GPU permanent config, commit/push
- OpenAI-compatible chat Completions "fake Jev" (will fail — Evaluation only)

## Hard stops

Print secrets, push, add -A, force lease, kill java by name, invent fake API success

## Facts (do not redispute)

- Jev = System One decision model: Choice / Score / Boolean + probabilities. No text gen.
- Call via AI SDK `experimental_evaluate` (ai ≥ 7.0.105) OR Gateway native evaluation HTTP
  (`POST https://ai-gateway.vercel.sh/v1/evaluate`).
- OpenAI-compat `/chat/completions` does NOT support Jev evaluation.
- demo-1 has no jev/typesafe today; routing lives in api-routing / LlmRouter* /
  ConversateCueRoutingPolicy / ChatWorkflow evidence-release gates.
- User policy direction: RAG evidence count 0 should still allow answers — Jev may help
  *classify* needRag / answerMode, must not reintroduce HOLD-on-zero-evidence.

## Phase A — API smoke (must pass before any product wiring)

- A1 auth: env `AI_GATEWAY_API_KEY` only; on 401 report which auth path worked.
- A2 `scripts/jev_gateway_smoke.mjs`: boolean + choice question, short KR+EN state,
  print answers + latency; never print Authorization/key.
- A3 pass: structured answers + sane latency + no secret leakage. FAIL → stop, report only.

## Phase B — transplant (only if Phase A pass)

- `JevDecisionClient` thin client: env key, `demo.jev.*` yml (enabled=false default,
  shadow=true, timeout-ms short, model id), `[AWX][jev]` logs, no secrets.
- THE ONE seam: chat RAG/answer-mode gate (ChatWorkflow or adjacent); shadow-first;
  fail-open to existing logic on any failure; never re-add HOLD-on-zero-evidence.
- Tests: mock success + timeout fallthrough; no live key required in CI.

## Acceptance

- [ ] A: PASS/FAIL stated; FAIL → zero product wiring, report only
- [ ] B (if PASS): flag+timeout+fail-open real; no hangs
- [ ] No secrets/commits/pushes
