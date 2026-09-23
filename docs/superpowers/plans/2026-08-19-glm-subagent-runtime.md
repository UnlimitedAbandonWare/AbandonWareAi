# GLM Subagent Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development and execute this tightly coupled plan sequentially. `subagent-driven-development` is not used for implementation because every task shares the same runtime interfaces; independent agents are reserved for final read-only review.

**Goal:** Build and prove the real `subagent.v1` main-agent to two-subagent execution path with GLM to OpenAI to Ollama fallback, bounded deadlines, redacted observability, synthesis, and HTTP acceptance.

**Architecture:** Add a small provider-neutral subagent package under the existing agent orchestrator. `Orchestrator` delegates only the exact new flow to a bounded runner; the runner uses the existing planner/synthesizer nodes and an ordered provider chain. Production providers reuse existing model adapters without changing their settings, while tests replace only the external call boundary.

**Tech Stack:** Java 17, Spring Boot, LangChain4j 1.0.1, JUnit 5, AssertJ, Spring Boot random-port testing, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-08-19-glm-subagent-runtime.md`

## Global Constraints

- Preserve `model=zai/glm-5.2`, Vercel base URL `https://ai-gateway.vercel.sh/v1`, Responses wire API, and environment-only `AI_GATEWAY_API_KEY` credential flow.
- Keep GLM network access disabled until key rotation and credits; automated verification performs zero Vercel calls.
- Do not modify existing provider configuration, Codex profiles, unrelated dirty files, prompts, responses, credentials, or guardrail settings.
- Preserve existing `Orchestrator` constructors and all non-`subagent.v1` behavior.
- Use fixed provider order `vercel-glm`, `openai`, `ollama`; cancellation never falls through.
- Store only categorical/redacted observability.
- No commit, push, merge, or deployment is authorized; commit steps normally required by the planning skill are intentionally replaced by diff inspection.

---

### Task 1: Provider chain contracts and RED tests

**Files:**
- Create: `src/test/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentProviderChainTest.java`
- Create: `main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentTask.java`
- Create: `main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentResult.java`
- Create: `main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentProvider.java`
- Create: `main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentProviderChain.java`

**Interfaces:**
- `SubagentTask(String requestId, int ordinal, String taskId, String role, String prompt, Map<String,Object> context)`
- `SubagentProvider.id()`, `order()`, `singleAttemptPerFlow()`, `availability()`, and `execute(SubagentTask,long)`.
  The `execute` name is intentional because the repository-wide reflection-debt guard reserves the method name `invoke` for dynamic-reflection detection.
- `SubagentProviderChain.execute(SubagentTask, AttemptBudget, long, Consumer<Attempt>)`
- `SubagentResult` owns status, output, selected provider, ordered attempts, and elapsed time.

- [ ] Write literal-behavior tests for GLM/OpenAI/Ollama ordering, disabled skips, stop-after-success, exception fallback, cancellation stop, remaining deadline, auth circuit, cooldown circuit, and one GLM attempt per flow.
- [ ] Run only `SubagentProviderChainTest` and verify compilation fails because the production contracts do not exist.
- [ ] Add the minimal records, provider interface, ordered chain, attempt budget, and circuit state.
- [ ] Re-run `SubagentProviderChainTest`; fix production code only until green.
- [ ] Inspect the task diff for secrets, raw messages, and undeclared files.

### Task 2: Planner, runner, synthesis, and Orchestrator branch

**Files:**
- Create: `src/test/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentFlowRunnerTest.java`
- Create: `src/test/java/com/abandonware/ai/agent/orchestrator/OrchestratorSubagentFlowTest.java`
- Create: `main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentFlowRunner.java`
- Modify: `main/java/com/abandonware/ai/agent/orchestrator/nodes/PlannerNode.java`
- Modify: `main/java/com/abandonware/ai/agent/orchestrator/nodes/SynthNode.java`
- Modify: `main/java/com/abandonware/ai/agent/orchestrator/Orchestrator.java`

**Interfaces:**
- `PlannerNode.subagentTasks(String requestId, Map<String,Object>)` returns exactly two independently owned task contexts.
- `SubagentFlowRunner.run(String,Map<String,Object>,ToolContext)` dispatches at most two tasks and returns the final state.
- `SynthNode.run(Map<String,Object>,List<SubagentResult>)` returns the final answer and synthesis status.
- Existing `SynthNode.run(Map)` and every existing `Orchestrator` constructor remain callable.

- [ ] Write tests proving distinct contexts, concurrent start, deterministic result order, task timeout, global deadline, partial synthesis, all-failed response, exact flow selection, and unchanged non-subagent behavior.
- [ ] Run the two test classes and verify RED for the missing branch/runner overloads.
- [ ] Add the bounded runner and minimal planner/synthesizer overloads, then inject it through an optional `ObjectProvider` while retaining existing constructors.
- [ ] Re-run the tests until green, then re-run existing agent recovery and controller tests.
- [ ] Inspect the task diff and current lease/preimages before continuing.

### Task 3: Production provider adapters and redacted telemetry

**Files:**
- Create: `main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentProviderConfiguration.java`
- Extend tests: `src/test/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentProviderChainTest.java`
- Extend tests: `src/test/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentFlowRunnerTest.java`

**Interfaces:**
- `vercel-glm` uses `OpenAiResponsesChatModel` with fixed base/model and a key supplier backed only by `System.getenv`.
- `openai` and `ollama` use the existing `DynamicChatModelFactory` and existing model/enable properties.
- Parent-thread telemetry is appended to `agent.subagent.events`.

- [ ] Add RED tests for GLM-disabled zero invocation, credential-shaped sentinel redaction, provider selection/failure/fallback telemetry, and 403/auth circuit behavior using deterministic invokers.
- [ ] Run the focused tests and verify the expected RED assertion failures.
- [ ] Add the three production provider beans and parent-thread redacted trace/log emission without modifying existing provider settings.
- [ ] Re-run focused tests until green and scan the diff for secret patterns.
- [ ] Inspect provider construction to prove no GLM call occurs while disabled.

### Task 4: Real random-port HTTP acceptance and regression proof

**Files:**
- Create: `src/test/java/com/abandonware/ai/agent/FlowControllerSubagentHttpAcceptanceTest.java`

**Interfaces:**
- A narrow nested `@SpringBootConfiguration` imports the real controller, orchestrator, runner, provider chain, planner/synthesizer path, and deterministic provider beans.
- JDK `HttpClient` posts JSON to a real random localhost port.

- [ ] Write the HTTP test asserting the complete main request to final synthesized response flow, provider fallback metadata, independent tasks, timeout case, and absence of raw sentinel credentials/prompts in trace metadata.
- [ ] Run it and verify RED before the narrow server configuration is complete.
- [ ] Add only the minimal test configuration required to exercise the real production path; do not add a production test mode.
- [ ] Run the HTTP test until green.
- [ ] Run focused subagent/recovery/ensemble tests, `compileJava`, and the broader root `test` task with isolated Desktop outputs and caches.
- [ ] Recheck target hashes, Codex configuration/profile hashes, lease state, `git diff --check`, and count-only secret patterns; then release the source-edit lease.
