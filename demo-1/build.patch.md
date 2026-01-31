# build.patch.md — demo-1 (RAG pipeline) minimal-diff

## Scope (demo-1 only)
Applied along the path:
ChatService → PromptBuilder → LlmRouterService → HybridEngineArbiter → DynamicRetrievalHandlerChain → OnnxCrossEncoderReranker → AnswerGovernor → Telemetry/Tracing

## Key changes
- Local-first routing with session stickiness and latency hedge (local→remote).
- Mode orchestration: SAFE / BRAVE / ZERO_BREAK via `HybridEngineArbiter` and `PromptBuilder.build(ctx)` only.
- GPU isolation: LLM=GPU0 (assumed by provider), ONNX=GPU1 via `zsys.onnx.device-id: 1` and semaphore guard.
- Soak/Probe ready (controllers already present in demo-1).

## Added/Updated files
```
src/main/java/com/abandonware/ai/agent/model/{ChatMode,ChatRequest,ChatResponse,ChatContext}.java
src/main/java/com/abandonware/ai/agent/prompt/PromptBuilder.java
src/main/java/com/abandonware/ai/agent/service/ChatService.java
src/main/java/com/abandonware/ai/agent/service/llm/{LlmRouterService,HybridEngineArbiter}.java
src/main/java/com/abandonware/ai/agent/rag/handler/DynamicRetrievalHandlerChain.java
src/main/java/com/abandonware/ai/agent/governance/AnswerGovernor.java
src/main/java/com/abandonware/ai/agent/onnx/OnnxRuntimeService.java
src/main/java/com/abandonware/ai/agent/config/{OnnxConfig,ProviderGuard}.java
src/main/java/com/abandonware/ai/agent/infra/trace/RequestTraceFilter.java
src/main/java/com/abandonware/ai/agent/rerank/OnnxCrossEncoderReranker.java  (updated to use semaphore)
src/main/resources/{application.yml,traits/{base,safe_mode,brave_mode,zerobreak_mode}.md}
build.gradle.kts (added LangChain4j 1.0.1 deps)
```

## Config (append to `src/main/resources/application.yml`)
```yaml
llm:
  provider: local-vllm
  latency-hedge-ms: 2000
  remote-timeout-ms: 5000

zsys:
  onnx:
    enabled: true
    device-id: 1
    max-concurrency: 2

gate:
  citation:
    min: 3
  finalSigmoid:
    enabled: true
    threshold: 0.5

probe:
  search:
    enabled: true
  admin-token: "CHANGE_ME"
```

## Build
```
cd project/src/demo-1
./gradlew bootJar    # if wrapper present; else: gradle bootJar
```

> Version purity: LangChain4j dependencies pinned to 1.0.1.
> Provider guard: app fails if `llm.provider` missing or env+property both set.

