# GLM agent auto-activation runbook

## Safety boundary

The MCP server keeps exactly four read-only tools. It never reads or changes workspace
files. The Codex host owns discovery, source-edit leases, RED/GREEN tests, patches, and
rollback decisions.

The existing `agent.subagent.glm.enabled` property is the auto-activation opt-in. The
only added readiness signal is `GLM_EXTERNAL_READY`. Do not add a second enable flag.
Never print, persist, hash, fingerprint, or report the value of
`AI_GATEWAY_API_KEY`.

The process may enter `READY_TO_PROBE` only when all of these facts are true:

- `agent.subagent.glm.enabled=true` (environment equivalent:
  `AGENT_SUBAGENT_GLM_ENABLED=true`)
- `GLM_EXTERNAL_READY=true`
- `AI_GATEWAY_API_KEY` is present and non-placeholder
- the existing GLM circuit is not blocking the attempt

`GLM_EXTERNAL_READY=true` is an operator attestation that a new key and Gateway
card/credit are ready. The agent must not issue or purchase either one.

## State and one-shot behavior

The readiness state is process-local and secret-free:

| State | Meaning | Automatic provider call |
| --- | --- | --- |
| `BLOCKED_EXTERNAL` | auto activation disabled, 403/credit policy, or an external prerequisite is absent | no |
| `WAITING_FOR_KEY` | readiness is confirmed but the key is missing | no |
| `WAITING_FOR_CREDIT_CONFIRMATION` | external readiness is not confirmed | no |
| `READY_TO_PROBE` | all local gates pass | one logical probe may be claimed |
| `PROBING` | one `glm_delegate_task` owns the process permit | no second probe |
| `GLM_ACTIVE` | the GLM result and matched provider/wire evidence are verified | normal GLM-first routing allowed |
| `GLM_DEGRADED` | client result exists but required evidence, deadline, or cancellation is incomplete | no re-probe in the same process |
| `GLM_AUTH_BLOCKED` | missing/invalid authorization or 401 | no automatic retry |
| `GLM_RATE_LIMITED` | terminal 429 | existing circuit/fallback applies |
| `GLM_TRANSIENT_FAILURE` | terminal network/5xx/provider failure | existing circuit/fallback applies |

Only `glm_delegate_task` can claim the first live probe. `glm_agent_status`,
`glm_review_change`, ordinary flows, and `glm_consensus_check` cannot consume it.
Concurrent or later delegates use fallback while a probe is in progress or after a
terminal non-active state. A process restart is the only implicit re-arm.

A logical probe may use the provider chain's existing single bounded retry for a 429
or qualifying transient 5xx while deadline remains. A 401 or 403 has no automatic
retry. Never rerun a 403 in the same readiness condition.

## Evidence and fallback

`GLM_ACTIVE` requires one request-local GLM success plus all of these redacted facts:

- `generationAttempted=true`
- `modelAdapterAttemptObserved=true`
- `clientHttpExchangeObserved=true`
- `clientHttpResponseObserved=true`
- `providerAttemptObserved=true`
- `wireAttemptObserved=true`
- response evidence is present as allowlisted hashes/booleans

An HTTP 200, an output string, a fallback success, client hashes, or delivery alone is
not provider/wire proof. The current Responses adapter records client-HTTP evidence but
does not manufacture provider or wire attestation. Until a same-request controlled
receipt supplies those two facts, a GLM HTTP success remains `GLM_DEGRADED` and the
missing attestation is `evidence_needed`.

The status payload keeps GLM probe fields separate from the aggregate result:

- `liveProbeProvider` and `liveProbeFailureClass` describe GLM
- `aggregateProvider`, `aggregateStatus`, and `liveProbeFallbackUsed` describe the
  selected fallback result
- `providerAttemptObserved` and `wireAttemptObserved` remain false unless controlled
  evidence proves them

OpenAI or Ollama success must never be reported as GLM success.

## Operator start and one live probe

Start a fresh MCP JVM only after the operator has completed the external work:

```powershell
if (-not (Test-Path Env:AI_GATEWAY_API_KEY)) { throw 'AI_GATEWAY_API_KEY is not present' }
$env:AGENT_SUBAGENT_GLM_ENABLED = 'true'
$env:GLM_EXTERNAL_READY = 'true'
java -jar .\build\desktop-glm-auto-final\glm-agent-mcp\glm-agent-mcp.jar
```

In that fresh process:

1. Call `glm_agent_status`; require `READY_TO_PROBE` and no generation attempt.
2. Call `glm_delegate_task` exactly once with a short, bounded structured reasoning
   task.
3. Call `glm_agent_status` again and compare the GLM probe fields with the aggregate
   provider fields.
4. Continue only for `GLM_ACTIVE` with every required evidence flag true. Otherwise
   retain the categorical terminal state and fallback result separately.

## GLM-active host queue

After verified activation, the Codex host runs this bounded sequence without granting
the MCP server workspace write access:

1. `glm_review_change`: review the supplied GLM/MCP diff for ordering, deadline,
   retry, circuit, cancellation, redaction, stdout purity, compatibility, and
   LangChain4j `1.0.1` purity.
2. `glm_consensus_check`: run SUPPORT and FALSIFY independently, then pass both
   completed bounded packets to NEUTRAL. Only a valid typed `APPLY` result sets
   `hostMutationAllowed=true`.
3. `glm_delegate_task` with `validateExecutionPacket=true`: require the typed fields
   `goal`, `evidence`, `affectedFiles`, `nonGoals`, `proposedPatch`, `redTest`,
   `greenTests`, `regressionTests`, `rollbackCondition`, `securityChecks`, and
   `unknowns`.
4. The host ranks a supplied evidence-backed GLM/MCP/RAG candidate, acquires the
   existing source-edit lease, verifies preimages, writes a focused RED, applies the
   smallest patch, and runs GREEN plus regression/build/security checks.
5. Invalid packets, non-`APPLY` neutral decisions, changed preimages, two failed repair
   loops, or missing evidence produce a lane-local `HOLD`; independent candidates may
   continue.

`expectedOutputSchema` remains an advisory prompt hint. Typed execution-packet
validation is a separate explicit step and never grants the MCP tool mutation rights.

## Metrics

The existing metrics snapshot includes activation/probe counts, provider attempts and
successes, fallback counts, categorical GLM failures, MCP tool calls/failures, queue
discovered/completed/failed/HOLD counts, perspective completed/cancelled/timed-out
counts, average latency, and p95 latency. A claimed activation with no provider dispatch
does not increment live-probe success/failure/fallback counters.

Logs remain categorical on stderr. Prompts, outputs, credentials, authorization
headers, raw HTTP bodies, full URL queries, and reconstructive credential fingerprints
are forbidden from logs, metrics, status, and reports.
