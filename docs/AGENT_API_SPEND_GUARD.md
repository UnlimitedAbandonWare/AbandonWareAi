# Agent API Spend Guard

> Applies to **Codex / agent / vibe-coding verification sessions**, not to normal end-user traffic.
> Production request paths must keep working without arbitrary small **spend** caps.
> Wall-clock timeouts are a separate contract and stay bounded: every chat/RAG/search
> request runs under `public.request-budget.max-time-budget-ms` (~300 s, single source
> `main/resources/application-llm.yaml`). Latency/progress display stays; on exhaustion
> the request stops or fails soft with an explicit timeout reason — never an open-ended wait.

## Spend/model order — SSOT pointer (2026-10-03)

The agent spend/model priority is **capability-first**:
`codex_credits → external_paid_api → free_tier → local_ollama`.
Codex (ChatGPT OAuth plan) credits **62,500 (as of 2026-10-03)** — user-reported
allocation, never an observed balance.

Single source of truth: `configs/agent-api-spend-guard.yaml` +
`.agents/skills/demo1-agent-api-spend-guard/SKILL.md`. Paid calls are ON by
default in agent sessions; `AWX_AGENT_ALLOW_PAID_MODELS` is a kill switch
(`=0`/`false`/`no`/`off` blocks; unset = ON). Purpose-picked parallel lanes
are allowed; identical-probe dedupe and successful-verification replay block
stay. Product runtime routing (`configs/api-routing.yaml` order) is NOT
governed here.

## Principles (agent/dev sessions only)

1. Do not repeat the same or near-identical API test in one session.
2. Do not re-call a verification that already succeeded unless the code or env under test changed.
3. Connectivity checks: minimum request, minimum tokens.
4. Call only the model required for the current purpose — parallel lanes must be purpose-picked.
5. Block stale auto-selection of high-cost GPT / legacy test models unless explicitly requested.
6. On failure: classify first (`missing_key|unauthorized|rate_limited|timeout|empty|network|disabled`), then at most **one** classified retry — never tight-loop; report the cause at the top.
7. Live calls stay bounded to ~25 per directive run unless the directive overrides.
8. **Never** put arbitrary small **spend** caps on normal production request paths — and never
   remove or bypass the bounded request wall clock (`public.request-budget.max-time-budget-ms`).

## Activation

Agent mode is ON when any of:

- env `AWX_AGENT_SPEND_GUARD=true|1`
- env `AWX_AGENT_HOST` is set (existing smoke scripts already set `desktop`)
- Spring profile `agent-spend-guard` is active

When OFF: only **attribution logs** are added (why a paid call happened). No blocking.

## Cost attribution log fields

Every outbound paid/cloud (and optionally local) call should emit `[AWX][api-spend]`:

| field | meaning |
|---|---|
| `session` | agent session / smoke run id |
| `purpose` | search\|asr\|embed\|llm\|vision\|hint\|probe\|soak |
| `provider` / `model` | selected backend |
| `tier` | free_local\|low_cost\|paid_quality |
| `why` | reason code for making the call |
| `caller` | class/script name |
| `cache` | `hit_skip`\|`miss`\|`forced` |
| `httpStatus` / `errorClass` | outcome |
| `promptTokens` / `completionTokens` | when provider returns usage |
| `estCostClass` | `local0`\|`search`\|`stt`\|`llm_cheap`\|`llm_paid` (never invent KRW) |

Never log API keys or Authorization headers.

## See also

- `configs/agent-api-spend-guard.yaml`
- `.agents/skills/demo1-agent-api-spend-guard/`
- `docs/API_ROUTING_SPEC.md`
- `docs/provider-limits/README.md` (public provider policy; not a live account/balance check)
