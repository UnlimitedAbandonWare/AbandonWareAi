---
name: demo1-glm-route-guard
description: Use when Codex wants GLM assist (glm_worker, glm-offload, GLM rebuttal/review) — native glm_worker spawn fails with HTTP 400 under the ChatGPT login; run the route preflight and use the glm_agent MCP lane or mark GLM=SESSION_UNAVAILABLE
---

# Demo1 GLM Route Guard

Fact (2026-10-02, `data/agent-handoff/devin-glm-native-400-f9416581/EVIDENCE/`):
a spawned native `glm_worker` inherits the parent's ChatGPT/OpenAI account path
(`model_provider: openai`), so its `zai/glm-*` request returns
`400 "not supported when using Codex with a ChatGPT account"`. The role file's
`model_provider = "vercel"` is not applied on that path — **native glm_worker
is structurally unsupported under the ChatGPT login; never retry it.**

## Order (every GLM assist decision)

1. `python -B scripts\glm_route_preflight.py` — read `route=` (add
   `--live-status` once per session if a live `glm_agent_status` answer is
   needed; it makes no provider call).
2. `MCP_READY` → use the `glm_agent` MCP tools (`glm_delegate_task`,
   `glm_review_change`, `glm_consensus_check`). `glm_agent_status` first is
   free; delegate only on `READY_TO_PROBE` / `GLM_ACTIVE` / `GLM_DEGRADED` and
   accept a result only when `provider` is `vercel-glm` with
   `fallbackUsed=false` — a `deterministic`/other provider is not GLM delivery.
3. `MCP_DISABLED_FLAGS` / `MCP_NOT_CONFIGURED` / `NATIVE_UNSUPPORTED_CHATGPT_LOGIN`
   / `KEY_MISSING`, or `BLOCKED_EXTERNAL` / `WAITING_*` from the status tool →
   record `GLM=SESSION_UNAVAILABLE(이유)` and continue with the parent's own
   exploration agents. No retry, no alternate GLM path.

## Rules

- Never spawn native `glm_worker` while the session is on a ChatGPT login
  (top-level `model_provider` absent in `~/.codex/config.toml` ⇒ ChatGPT path).
- GLM is read-only assist: no code writes, config changes, secrets, or
  irreversible decisions are delegated; final judgment and all edits stay with
  the parent. Only the minimum paths/symbols/question cross the wire.
- Keep the `deliveryMarker` contract: embed a random non-sensitive marker and
  require `task_received=<marker>` as the first line of `finding`; HTTP 200 or
  plausible text alone is invalid (`scripts/selfask_triad.py` MARKER_RE).
- Call cap: ≤5 GLM calls per task. HTTP 400/401/403/429 or credit errors =
  no retry that session — classify on the report's `외부 API:` line per
  `docs/API_ROUTING_SPEC.md` (401 `KEY_INVALID_OR_EXPIRED`; 403 →
  `PLAN_GATE` vs `FORBIDDEN_REGION_OR_IP`).
- The readiness env gate (`AGENT_SUBAGENT_GLM_ENABLED`, `GLM_EXTERNAL_READY`)
  is a user-level decision — report, never self-edit `~/.codex/config.toml` or
  global skills.

Related: `glm-offload` (global, marker/delegation contract),
`demo1-codex-plugin-roles` (GLM lane policy), `demo1-agent-api-spend-guard`.
