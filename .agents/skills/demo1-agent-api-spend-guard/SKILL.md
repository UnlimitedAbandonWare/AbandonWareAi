---
name: demo1-agent-api-spend-guard
description: "Use during demo-1 vibe-coding/verification when API credits may burn from probes"
---

# Demo1 Agent API Spend Guard

## Read first

- `configs/agent-api-spend-guard.yaml` (machine SSOT)
- `docs/AGENT_API_SPEND_GUARD.md`
- `docs/API_ROUTING_SPEC.md`

## Agent spend order (SSOT — 2026-10-03)

For agent sessions (Codex / Devin / Grok CLI / Cline vibe coding and
verification) the model·cost priority is **capability-first** — strong models
and purpose-fit parallel lanes, not savings-first:

1. **codex_credits** — ChatGPT OAuth plan lane (`chatgpt-oauth:*`, Codex CLI).
   Codex credits 62,500 (as of 2026-10-03) — a user-reported allocation,
   never an observed balance.
2. **external_paid_api** — OpenAI / Anthropic / Gemini / Groq paid keys,
   OpenRouter paid, Vercel AI Gateway USD (separate budget — never mix units).
3. **free_tier** — free provider tiers / gratis lanes.
4. **local_ollama** — local Ollama on the RTX 3090.

Product runtime routing is NOT governed here — `configs/api-routing.yaml`
`policy.order` (free_local → low_cost → paid_quality) stays untouched.

**Paid is ON by default** for agent sessions. `AWX_AGENT_ALLOW_PAID_MODELS`
is a **kill switch**, not an opt-in: `=0`/`false`/`no`/`off` blocks paid
calls; unset or any other value keeps the default ON.

## Before any live cloud call in an agent session

1. Set or inherit `AWX_AGENT_HOST` / `AWX_AGENT_SPEND_GUARD=1`.
2. Ask: did this exact verification already succeed this session? If yes, **do not** call again.
3. Pick the model the purpose needs per the spend order above; connectivity = tiny prompt, tiny `max_tokens`.
4. Purpose-picked parallel lanes (the right model per role) are allowed — identical-probe dedupe and successful-verification replay block still apply.
5. Do not let `models.manifest.yaml` `gpt-4` defaults silently win — pick the purpose model from `api-routing.yaml`.
6. On failure: classify (`unauthorized|rate_limited|timeout|empty|network|disabled`) then at most one retry; put the cause at the top of the report.
7. Log with `ApiSpendAttribution.record(...)` / `[AWX][api-spend]` including `why`.
8. Live API calls stay bounded to ~25 per directive run (browser self-check basis) unless the directive overrides.

## Do not

- Add arbitrary per-request **spend** caps on production chat/RAG paths, or treat
  `production_hard_caps: false` as a timeout waiver — the request wall clock
  (`public.request-budget.max-time-budget-ms` ~300 s; `docs/API_ROUTING_SPEC.md`
  "Chat wait and fallback policy") stays bounded: waits keep latency/progress 표기
  but end in stop/fail-soft + an explicit timeout reason.
- Clear and re-inject cloud keys just to "re-prove" a green check.
- Fan out blindly "to be safe" — parallel lanes must be purpose-picked, and a
  successful verification is never replayed for reassurance.

## Verification completion (no token-save early stop)

A **declared** verification runs to completion inside the call budget —
token-save is NOT a default stop reason. Stop only when:

1. The user explicitly says they will verify themselves / asks to stop.
2. The call budget (~25 live calls per directive run) or a hard constraint is hit.
3. Do not stop or force-restart a Spring/RAG process you did not start (AGENTS: manage only task-started processes) unless the user explicitly approves that PID.
4. Report clearly: erification=tests-green, liveRuntime=not-activated|unowned, 
ext=user-self-verify|await-restart-approval.
5. Keep the three money sources separate — never quote one for another:
   (a) **ChatGPT OAuth plan credits** — `authorized_credit_budget: 62500` is a
       user-reported *allocation input*, not an observed balance (observed
       balance stays `null`/unknown; reported expiry `2026-12-31`, timezone
       unconfirmed). Unit = credits.
   (b) **Vercel AI Gateway (Jev·glm)** — separate **USD** balance
       (~$22 observed 2026-09-24) drawn via `AI_GATEWAY_API_KEY`; the 62,500
       credit figure never applies to Gateway spend.
   (c) **Free/local APIs** — lower tiers in the spend order (free_tier →
       local_ollama), used when the purpose does not need a paid lane.
   The withdrawn R2 `STRICT_ZERO` (additional spend = $0) posture stays
   retired — do not reintroduce it as a default.