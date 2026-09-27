# GLM Subagent Runtime Design

## Status and scope

This design is approved for implementation on the Desktop canonical checkout.
It adds one explicit flow, `subagent.v1`, without changing existing chat flows,
Codex profiles, or existing provider configuration.

Actual Vercel GLM generation remains `BLOCKED_EXTERNAL` until the exposed key
has been replaced and AI Gateway credits are active. Local implementation and
verification must make zero Vercel requests before that condition is met.

## Required request flow

`POST /flows/subagent.v1:run` must execute this production path:

1. `FlowController` creates the existing `ToolContext` and calls
   `Orchestrator.execute`.
2. `Orchestrator` selects the exact `subagent.v1` branch. Other flow IDs retain
   the current critic/recovery behavior.
3. `PlannerNode` creates exactly two bounded tasks, `analysis` and
   `verification`, from a scalar snapshot of the request. Each task owns a
   distinct immutable context map.
4. `SubagentFlowRunner` dispatches both tasks concurrently on a bounded
   two-thread executor.
5. Each task calls `SubagentProviderChain` with the remaining task/global
   deadline. Providers are considered in this exact order:
   `vercel-glm`, `openai`, `ollama`.
6. Results and categorical failures are collected in original task order.
7. `SynthNode` combines successful result text once. If one task failed, the
   response is degraded but still synthesized. If both fail, it returns a
   stable redacted failure answer.
8. The final state is returned through the real HTTP controller.

## Provider policy

### Vercel GLM

- Model: `zai/glm-5.2`
- Base URL: `https://ai-gateway.vercel.sh/v1`
- Wire API: OpenAI Responses
- Credential: `System.getenv("AI_GATEWAY_API_KEY")` only
- Default: disabled through `agent.subagent.glm.enabled=false`
- No raw key, prompt, response, request body, response body, or exception body
  may enter application logs, traces, tests, or reports.
- A per-flow atomic budget permits at most one GLM attempt, even though two
  tasks run concurrently.
- A 401/403 classification opens a process-local auth circuit. Later tasks and
  requests skip GLM without network access until process restart.
- Transient provider failures open a bounded cooldown circuit.

### OpenAI

- Enabled only when the existing
  `llmrouter.models.openai-balanced.enabled` route is enabled and the existing
  `DynamicChatModelFactory` reports the configured model can be served.
- Model defaults to the existing
  `llmrouter.models.openai-balanced.name` value.
- Existing OpenAI credential resolution and provider guards are reused.

### Ollama

- Enabled by default and uses the existing `llm.fast.model` plus
  `DynamicChatModelFactory`, including its native Ollama selection.
- Existing endpoint, owner-token, private-host, model, and credential settings
  remain authoritative and unchanged.

## Deadlines and errors

- `agent.subagent.deadline-ms` defaults to `15000`.
- `agent.subagent.task-timeout-ms` defaults to `10000` and is clamped to the
  global deadline.
- `agent.subagent.provider-cooldown-ms` defaults to `60000`.
- Provider attempts receive only the remaining deadline. Cancellation never
  triggers another fallback.
- Exceptions are reduced to existing `LlmFailureClass` values. HTTP responses
  expose provider IDs, outcomes, failure classes, elapsed milliseconds, and
  fallback flags, but never exception messages or provider output outside the
  final synthesized answer.

## Observability

The parent request thread appends allowlisted rows to
`agent.subagent.events` and emits structured SLF4J messages for:

- plan creation;
- task dispatch;
- provider selected/skipped/succeeded/failed;
- task timeout or global deadline;
- result collection;
- synthesis completion.

Rows contain only flow/request/task IDs or hashes, provider ID, outcome,
failure class, fallback flag, counts, and elapsed milliseconds. Worker threads
do not write raw `TraceStore` state; their redacted attempt records are merged
on the parent thread.

## Compatibility and safety

- Preserve all existing `Orchestrator` constructors.
- Do not modify `FlowController`, existing YAML provider settings, Codex
  `config.toml`, `glm.config.toml`, or any other profile.
- Do not alter `ChatWorkflow`, `DiverseSamplingOrchestrator`,
  `DynamicChatModelFactory`, `OpenAiResponsesChatModel`, or the existing
  provider failure classifier because those files contain unrelated user
  changes.
- New behavior is selected only by the exact flow ID `subagent.v1`.
- No external provider is called by automated tests. Production provider
  boundaries are replaced with deterministic in-process test doubles.

## Acceptance evidence

- RED tests must fail because the new types/branch do not exist before
  production code is written.
- Unit tests cover provider order, disabled-provider skipping, success stop,
  exception fallback, timeout propagation, global deadline, per-flow GLM
  single-attempt behavior, auth circuit, independent task contexts, result
  ordering, partial synthesis, and all-failed synthesis.
- A random-port Spring Boot test must send real JSON over HTTP to
  `/flows/subagent.v1:run?trace=on`, use the real controller, orchestrator,
  runner, chain, planner, and synthesizer, and replace only external providers.
- Existing focused recovery/ensemble tests, `compileJava`, and the relevant
  broader test suite must pass with isolated Desktop Gradle caches.

