# Patch Notes for `src_47.zip`

This document summarises the changes introduced when producing `src_47.zip` from the provided `src_46 (2).zip` codebase.  The goal of this iteration was to incorporate a unified tool/skill layer, consent management, durable job infrastructure, a mini orchestrator, observability hooks and a context bridge into a single Spring Boot bundle.

## Added

* **New Spring Boot module** under `app/` containing a standalone agent application.  The module uses Java 17, Spring Boot 3.1 and Gradle Kotlin DSL.
* **Tool layer** under `com.abandonware.ai.agent.tool` including:
  * `AgentTool`, `ToolRequest`, `ToolResponse` and `ToolContext` abstractions.
  * `ToolScope` enumeration defining fixed scope identifiers.
  * `RequiresScopes` annotation and `ToolScopeAspect` to enforce scope checks at runtime.
  * A `ToolRegistry` for dynamic discovery and registration of tools.
  * Seven stub tool implementations: `KakaoPushTool`, `N8nNotifyTool`, `PlacesSearchTool`, `GeoReverseTool`, `RagRetrieveTool`, `WebSearchTool` and `JobsEnqueueTool`.  These tools correspond to the identifiers defined in the tool manifest.
* **Consent management** under `com.abandonware.ai.agent.consent` including:
  * `ConsentToken`, `Grant`, `ConsentService` interface and in‑memory `BasicConsentService` implementation.
  * `ConsentCardRenderer` for rendering basic v2.0 Kakao consent cards from a template.
  * `ConsentRequiredException` for signalling missing scopes.
* **Durable jobs** under `com.abandonware.ai.agent.job` including:
  * Domain objects (`JobId`, `JobRequest`, `JobResult`, `JobRecord`, `JobState`).
  * `JobQueue` interface with an in‑memory implementation (`InMemoryJobQueue`).
  * `DurableJobService` facade for enqueuing jobs.
* **Mini orchestrator** under `com.abandonware.ai.agent.orchestrator` including:
  * YAML‑driven `FlowDefinition`, `Step` and `NodeType` representations.
  * `FlowDefinitionLoader` for loading flow definitions from `src/main/resources/flows/`.
  * `Orchestrator` to execute flows by invoking registered tools, with simple expression resolution and scope checks.
* **Stub integrations** under `com.abandonware.ai.agent.integrations` for Kakao messaging, n8n notifications, place search, reverse geocoding, hybrid retrieval and web search.
* **Observability stubs** under `com.abandonware.ai.agent.observability` providing `AgentTracer` and `AgentMetrics`.
* **Policy stubs** under `com.abandonware.ai.agent.policy` including `BudgetGuard` and `ToolPolicyEnforcer`.
* **Context bridging** under `com.abandonware.ai.agent.context` including `ChannelRef` and `ContextBridge`.
* **Application configuration** (`AgentConfiguration`) wiring together tools, consent service, job queue, flow loader and orchestrator.
* **Sample resources** including `application.yml` with placeholder configuration, a `kakao_ask.yaml` flow, a `kakao_consent_card.basic.json` template and copies of the system prompt and tool manifest in the `docs/` folder.
* **Documentation**: new `README.md`, `HOW_TO_APPLY.md` and `PATCH_NOTES.md` at the project root explaining how to build, run and integrate the agent.
* **Monitoring assets** under `ops/` with example Grafana dashboard and Prometheus rules (placeholders).

## Removed/Updated

* No files from the original `src_46 (2).zip` codebase were deleted.  Instead, the new agent module is added alongside the existing LMS code without modifying the legacy classes.  The agent module is self‑contained and can be built independently.

## Notes

* The stub integrations do not perform any real HTTP calls; they log the invocation parameters and return empty results.  Replace these classes with proper implementations before using in production.
* The consent service, job queue and context bridge use in‑memory data structures for simplicity.  Consider swapping these out for Redis or database backed implementations when deploying at scale.
