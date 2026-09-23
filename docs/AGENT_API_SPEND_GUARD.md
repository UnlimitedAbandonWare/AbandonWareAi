# Agent API Spend Guard

> Applies to **Codex / agent / vibe-coding verification sessions**, not to normal end-user traffic.
> Production request paths must keep working without arbitrary hard caps.

## Problem we observed

Credits can burn during a single agent session even when service traffic is low, because:

1. `scripts/chat_ui_vibe_soak.ps1` and related vibe/soak loops re-run live probes.
2. `scripts/device_probe_gpt_*` fan out multi-role GPT device probes/comparisons.
3. Many smoke scripts export/clear `OPENAI_API_KEY` / search keys and still boot paths that can hit cloud.
4. `configs/models.manifest.yaml` and some profile YAMLs still default aliases to `gpt-4`.
5. `llm.gateway.cloud.enabled` defaults to **true** (`LLM_GATEWAY_CLOUD_ENABLED`), with route key `api3`.
6. Fallback / ensemble / Self-Ask lanes can escalate to cloud (`llmrouter.api3`) after local issues.
7. Retry/backoff exists (Brave 429, OpenAI retry budget, circuit maxRetries=2) — fine for prod, dangerous if an agent repeatedly restarts the same failing probe.

## Principles (agent/dev sessions only)

1. Do not repeat the same or near-identical API test in one session.
2. Do not re-call a verification that already succeeded unless the code or env under test changed.
3. Connectivity checks: minimum request, minimum tokens, local-first.
4. Call only the model required for the current purpose — no shotgun multi-model.
5. Block stale auto-selection of high-cost GPT / legacy test models unless explicitly requested.
6. On failure: classify first (`missing_key|unauthorized|rate_limited|timeout|empty|network|disabled`), then at most **one** classified retry — never tight-loop.
7. Increase call volume only for deliberately staged large tests.
8. **Never** put arbitrary small hard caps on normal production request paths.

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
