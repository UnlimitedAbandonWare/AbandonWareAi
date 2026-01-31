# README — demo-1 RAG enhancements (merge83)

- Local-first LLM with fallback hedge. Conversation stickiness by `sessionId`.
- Modes:
  - SAFE: official sources only + strict sanitization.
  - BRAVE: deep retrieval + ≥3 citations + rerank on GPU1.
  - ZERO_BREAK: single-hop, fast answer.
- GPU isolation: LLM on GPU0 (provider config), ONNX reranker on GPU1 (`zsys.onnx.device-id: 1`) with semaphore limit.
- Telemetry/trace: `X-Request-Id` and `X-Session-Id` -> MDC via `RequestTraceFilter`.

## Quick start
1. Set `llm.provider` in `application.yml` (or profile). Do **not** set `LLM_PROVIDER` env simultaneously.
2. Run Spring Boot: `./gradlew bootRun` (Java 17). Endpoint examples:
   - `/api/probe/search` (probe; token via `probe.admin-token`)
   - Existing controllers in demo-1 remain unchanged.

## Notes
- All prompts flow through `PromptBuilder.build(ctx)`; no ad-hoc String concatenation.
- LangChain4j pinned to 1.0.1 for API purity.
