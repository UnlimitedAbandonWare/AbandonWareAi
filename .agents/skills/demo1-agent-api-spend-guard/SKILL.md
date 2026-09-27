---
name: demo1-agent-api-spend-guard
description: "Use during demo-1 vibe-coding/verification when API credits may burn from probes"
---

# Demo1 Agent API Spend Guard

## Read first

- `docs/AGENT_API_SPEND_GUARD.md`
- `configs/agent-api-spend-guard.yaml`
- `docs/API_ROUTING_SPEC.md`

## Before any live cloud call in an agent session

1. Set or inherit `AWX_AGENT_HOST` / `AWX_AGENT_SPEND_GUARD=1`.
2. Ask: did this exact verification already succeed this session? If yes, **do not** call again.
3. Prefer Ollama / local OpenAI-compat. Connectivity = tiny prompt, tiny `max_tokens`.
4. Do not run `device_probe_gpt_run_all`, vibe soak, or multi-model compare unless the user explicitly asked for that scale.
5. Do not let `models.manifest.yaml` `gpt-4` defaults silently win — pick the purpose model from `api-routing.yaml`.
6. On failure: classify (`unauthorized|rate_limited|timeout|empty|network|disabled`) then at most one retry.
7. Log with `ApiSpendAttribution.record(...)` / `[AWX][api-spend]` including `why`.

## Do not

- Add arbitrary per-request hard caps on production chat/RAG paths.
- Clear and re-inject cloud keys just to "re-prove" a green check.
- Fan out Groq+Gemini+OpenAI+Anthropic "to be safe".

## User self-verify stop (token save)

When focused/unit verification is already green and the only remaining step is an **unowned** live server restart or a quick Fold6 eyeball check the user can do in seconds:

1. Prefer **stop** if the user says they will verify themselves / wants to save tokens.
2. Do not start paid browser probes, multi-question live soak, or Exa/web fanout just to "finish" that last mile.
3. Do not stop or force-restart a Spring/RAG process you did not start (AGENTS: manage only task-started processes) unless the user explicitly approves that PID.
4. Report clearly: erification=tests-green, liveRuntime=not-activated|unowned, 
ext=user-self-verify|await-restart-approval.