# How to Merge the Agent into an Existing Service

This document provides high‑level guidance on merging the K‑CHAT agent module (`app` in this repository) into an existing Spring Boot service.  The aim is to integrate the tool/skill layer, consent management, durable jobs, orchestration, observability and context bridging without disrupting existing functionality.

## 1. Extract the Agent Module

The `app` directory contains a standalone Spring Boot application.  Copy the contents of this directory into your existing service under a new module (for example `agent-module`).  Ensure that the `build.gradle.kts` file is aligned with your build system and that the source folder structure (`src/main/java` and `src/main/resources`) matches your conventions.

## 2. Register Beans

Import the agent configuration into your main application context.  If you are using Java configuration, add the following to one of your `@Configuration` classes:

```java
@Import(com.abandonware.ai.agent.config.AgentConfiguration.class)
```

This will register the tool registry, consent service, job queue, orchestrator and stub integrations.  Alternatively, copy the bean declarations from `AgentConfiguration` into your own configuration class and customise them as needed.

## 3. Wire Endpoints

The sample agent does not expose any REST controllers.  To make use of the agent you should create your own controller or service that accepts user requests, constructs a `ToolContext` with the appropriate session ID and consent token, and invokes the `Orchestrator` with the name of the desired flow.  Inspect the returned map to determine whether the invocation succeeded or if a consent challenge should be surfaced to the user.

Example:

```java
@RestController
class AgentController {
  private final Orchestrator orchestrator;
  // inject ToolRegistry, ConsentService, etc.

  @PostMapping("/agent/ask")
  public ResponseEntity<?> ask(@RequestBody Map<String,Object> body, HttpSession session) {
    String sessionId = session.getId();
    ConsentToken token = new ConsentToken(sessionId);
    ToolContext ctx = new ToolContext(sessionId, token);
    try {
      Map<String,Object> result = orchestrator.runFlow("kakao_ask", body, ctx);
      return ResponseEntity.ok(result);
    } catch (ConsentRequiredException e) {
      // render consent card
    }
  }
}
```

## 4. Replace Stub Integrations

The provided implementations of `KakaoMessageService`, `N8nNotifier`, `KakaoPlacesClient`, `KakaoReverseGeocodingClient`, `HybridRetriever` and `TavilyWebSearchRetriever` are stubs that do not perform any real network I/O.  Replace these classes with your own clients that integrate with the respective external systems.  The method signatures are intentionally simple and should be straightforward to map to HTTP calls using `WebClient` or `RestTemplate`.

## 5. Persist Consent Grants and Jobs

In this sample, consent grants and jobs are stored in memory.  For a production deployment you should:

* Implement a `ConsentService` backed by a database or cache such as Redis to persist user grants across service restarts.
* Provide a `JobQueue` implementation that uses a durable queue (e.g. Redis, Kafka) to reliably store jobs.  The `DurableJobService` will work unchanged against any implementation of `JobQueue`.

## 6. Extend the Flow Definitions

Flow definitions reside in `src/main/resources/flows/`.  You can add new YAML files to declare additional flows.  The orchestrator will automatically load the appropriate file when `runFlow` is invoked.  Ensure that any new tools referenced in flows are registered in the `ToolRegistry` via the configuration.

## 7. Instrumentation

The classes in `com.abandonware.ai.agent.observability` provide a minimal tracing and metrics facility.  For real observability, integrate the agent with Micrometer and OpenTelemetry.  Replace `AgentTracer` and `AgentMetrics` with wrappers around these libraries and add appropriate exporters to your application.

---

By following the steps above you can incorporate the agent's capabilities into your existing service while maintaining clear separation between core business logic and AI orchestration.