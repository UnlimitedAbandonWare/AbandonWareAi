# Resilience And Observability Map

## Existing Components

- Request trace state:
  - `main/java/com/example/lms/search/TraceStore.java`
  - `main/java/com/example/lms/trace/TraceContext.java`
- Debug event ring buffer:
  - `main/java/com/example/lms/debug/DebugEventStore.java`
  - `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java`
- SSE publishers:
  - `main/java/com/example/lms/telemetry/SseEventPublisher.java`
  - `main/java/com/example/lms/telemetry/DefaultSseEventPublisher.java`
  - `main/java/com/example/lms/telemetry/LoggingSseEventPublisher.java`
- Search starvation:
  - `main/java/com/example/lms/service/NaverSearchService.java`
  - `main/java/com/example/lms/service/web/BraveSearchService.java`
  - `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`
- Error Break and failure pattern:
  - `main/java/com/example/lms/service/rag/handler/NovaErrorBreakGuard.java`
  - `main/java/com/example/lms/resilience/ErrorBreakTelemetryAspect.java`
  - `main/java/com/example/lms/cfvm/*`
- Silent failure / nightmare detection:
  - `main/java/ai/abandonware/nova/boot/reactor/NovaReactorDroppedErrorHook.java`
  - `main/java/com/example/lms/service/trace/DebugCopilotService.java`
- Autolearn and dataset loop:
  - `main/java/com/example/lms/uaw/autolearn/*`
  - `main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`
  - `main/java/com/example/lms/dataset/DatasetWriter.java`
  - `main/resources/data/train_rag.jsonl`
- Virtual matrices / experience vectors:
  - `main/java/com/example/lms/telemetry/MatrixTelemetryExtractor.java`
  - `main/java/com/example/lms/learning/VirtualPointService.java`
  - `main/java/com/example/lms/learning/virtualpoint/*`
- Ablation:
  - search for `ablation.probabilities`, `Ablation`, `Boltzmann`, and `qtx.llm.modelRequired`.

## Stable Diagnostic Shape

Use maps with simple scalar values:

```text
provider
enabled
hasKey
keySource
endpointHost
queryHash
requestedCount
returnedCount
afterFilterCount
disabledReason
timeoutMs
tookMs
rateLimitState
domainPolicy
failureClass
hotspot
cycleId
laneCoverage
contaminationScore
requeryConfirmed
```

## Failure Classification

- `missing-key/provider-disabled`: provider intentionally skipped due missing or invalid key.
- `zero-result`: provider called successfully and returned no usable records.
- `after-filter-starvation`: raw provider records existed but filters/rerank/domain policy removed all.
- `timeout`: request exceeded provider or global budget.
- `rate-limit`: HTTP 429, local cooldown, or breaker skip.
- `silent-failure`: stage returned a default/blank/missing future while upstream appeared successful.
- `context-contamination`: low diversity, raw generated logs, build artifacts, or poison metadata entered vector/memory candidates.

