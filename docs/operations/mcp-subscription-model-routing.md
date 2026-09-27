# MCP subscription-aware model routing

The existing four `glm-agent` tools remain the entry points. The STDIO server now also registers an MCP-only `devin-cli` provider. It reuses the installed `devin-research-delegation/scripts/devin_cli.py` bridge; it does not implement another Devin client or enable Devin Cloud.

## Public code review

For code already reviewed by the parent agent as wholly public or synthetic, call `glm_review_change` with:

```json
{
  "diff": "<bounded public code or change>",
  "reviewRubric": "Check correctness and identify concrete counterexamples.",
  "context": {"publicCodeReview": true, "devinEffort": "medium"},
  "timeoutMs": 110000
}
```

`publicCodeReview` is an explicit caller attestation, not an automatic privacy certification. Do not set it for private source or prompts. The provider also screens known sensitive patterns before starting a process. `glm_delegate_task` can use the same context with role `counterexample_search`; general reasoning and consensus roles do not run through this code-review-only bridge.

Public review requests automatically use `costPolicy=subscription_only`. Providers without explicit subscription/local eligibility are excluded before readiness or execution. A failure may use the existing local Ollama route; it never switches to OpenAI, Vercel, an unclassified provider, or another SWE-2 variant. Explicit invalid cost policies fail closed. Requests without the public review attestation or a cost policy retain the configured legacy provider chain.

`devinEffort` accepts `medium` (default), `high`, or `max`, selecting the exact corresponding `swe-2-*` variant. The bridge requires fresh account-catalog `cost_tier=Free` evidence, authentication, the trusted empty workspace, and a matching successful canary before each review. Missing evidence returns a categorical failure. Higher effort is opt-in; the provider does not try multiple models. One review is attempted per flow and it is not retried.

The process budget reserves 50 seconds for the bridge's bounded authentication, catalog checks and cleanup. At least 51 seconds of remaining task budget is needed. The generation limit is the remaining budget, capped at 90 seconds. Output is capped at 64 KiB. Temporary public packet files are removed in `finally`; stderr and raw packet/response contents are not logged by this adapter. Timeout/cancellation terminates only the child process and captured descendants owned by that invocation. The existing bridge owns its request lock and redacted receipts; unknown or unreconciled locks are never deleted by this adapter.

The result identifies `selectedModel` and `billingEvidence=catalog_Free`. `servedModel` and `usage` remain `not_observed`: CLI/catalog evidence is not an independent billing ledger or wire trace.

MCP-only host settings:

| Property | Default |
| --- | --- |
| `agent.subagent.devin.enabled` | `true`; execution still requires the public-input/task/cost gates |
| `agent.subagent.devin.python` | `python` on the host PATH |
| `agent.subagent.devin.bridge-path` | `${user.home}/.codex/skills/devin-research-delegation/scripts/devin_cli.py` |

`glm_agent_status` does not run authentication, model-catalog or generation commands. A present CLI/bridge is reported as `cli_present_catalog_unchecked`; that state does not assert that a Free review can complete. The ordinary Spring web runtime does not register the CLI provider.

## GLM configuration

The existing Vercel provider accepts these trusted host properties:

| Property | Default |
| --- | --- |
| `agent.subagent.glm.model` | `zai/glm-5.3-flash` |
| `agent.subagent.glm.reasoning-effort` | `medium` |
| `agent.subagent.glm.review-model` | The general GLM model |
| `agent.subagent.glm.review-reasoning-effort` | `high` |

The review profile applies to `review`, `counterexample_search`, `falsify`, `neutral`, and `synthesis`. Models must be GLM identifiers in the `zai/glm-*` namespace; reasoning accepts `low`, `medium`, or `high`. Unresolved placeholders or invalid configuration fail closed with `glm_model_configuration_invalid`. MCP prompt/context fields cannot change the provider URL or credentials. Existing `agent.subagent.glm.enabled`, `GLM_EXTERNAL_READY`, key, activation, deadline, and single-attempt gates remain in force.

This route still uses Vercel AI Gateway and its existing credential. It does not claim GLM Coding Plan eligibility or automatically enable a paid route.

## Promotion boundaries

Official information checked on 2026-09-13:

- [Devin pricing](https://devin.ai/pricing): the SWE-2 benefit is for Desktop/CLI through October 10, 2026, separate from the subscription price. The adapter does not hard-code perpetual Free eligibility; the live account catalog must still confirm it.
- [GLM-5.3-Flash campaign](https://docs.z.ai/devpack/notice/event-glm-5.3-flash): paid Coding Plan users, September 3–20, daily 23:00–09:00 UTC+8 (00:00–10:00 Korea). ZCode 3.10+ has zero quota consumption, while other supported agents have doubled quota. Exhausted quotas can still prevent use.
- [Coding Plan usage policy](https://docs.z.ai/devpack/usage-policy): supported-tool eligibility is required. A custom MCP/Vercel request is not treated as an eligible ZCode or Coding Plan request.

Building a new JAR does not replace already-running MCP processes. Use the newly built JAR in a new STDIO process for verification; restart the configured MCP through its owner before expecting an existing connection to use changed source.
