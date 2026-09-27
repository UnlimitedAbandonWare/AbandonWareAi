package com.example.lms.web;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.trace.TraceSnapshotStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Iterator;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUiHeartbeatPayloadTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        AtomicLong lastLocalSuccess = (AtomicLong) ReflectionTestUtils.getField(
                ModelRuntimeHealthTracker.class,
                "LAST_LOCAL_SUCCESS_EPOCH_MS");
        if (lastLocalSuccess != null) {
            lastLocalSuccess.set(0L);
        }
    }

    @Test
    void publicHeartbeatOmitsInternalProviderAndTopologyFacts() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("capturedAt", "2026-06-27T10:15:30");
        source.put("status", "WARN");
        source.put("reason", "agent_db_context_disabled");
        source.put("nextAction", "enable_agent_db_context_for_full_pipeline_health");
        source.put("providerStatus", List.of(Map.of(
                "provider", "PRIVATE_SENTINEL_67",
                "route", "private-route",
                "model", "private-model",
                "credentialPresent", true)));
        source.put("providerRuntime", Map.of("host", "private-host"));
        source.put("environment", Map.of("path", "C:\\private\\path"));
        source.put("projectRef", "PRIVATE_SENTINEL_67");

        Map<String, Object> payload = ChatUiHeartbeatPayload.from(source);
        String json = OBJECT_MAPPER.writeValueAsString(payload);

        assertEquals(Set.of("statusBand", "reasonCode", "nextAction", "ageMs"), payload.keySet());
        assertEquals("WARN", payload.get("statusBand"));
        assertEquals("degraded", payload.get("reasonCode"));
        assertEquals("retry_later", payload.get("nextAction"));
        assertEquals(0L, payload.get("ageMs"));
        assertFalse(json.contains("PRIVATE_SENTINEL_67"));
        assertFalse(json.contains("provider"));
        assertFalse(json.contains("route"));
        assertFalse(json.contains("model"));
        assertFalse(json.contains("credential"));
        assertFalse(json.contains("environment"));
        assertFalse(json.contains("projectRef"));
        assertFalse(json.contains("path"));
        assertFalse(json.contains("host"));
    }

    @Test
    void privateProviderStatusIsNotProjected() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", "gemini");
        row.put("route", "search-expansion");
        row.put("model", "gemini-2.5-flash");
        row.put("enabled", true);
        row.put("credentialPresent", true);
        row.put("attemptCount", Integer.MAX_VALUE);
        row.put("statusCode", 200);
        row.put("latencyMs", Long.MAX_VALUE);
        row.put("cacheHit", false);
        row.put("quotaDecision", "allowed");
        row.put("fallbackReason", "none");
        row.put("errorClass", "none");
        row.put("rawPrompt", "private prompt must not render");
        row.put("authorization", "Bearer private-token");
        row.put("endpoint", "https://private.example/v1/models");

        Map<String, Object> payload = ChatUiHeartbeatPayload.from(Map.of("providerStatus", List.of(row)));

        assertEquals(Set.of("statusBand", "reasonCode", "nextAction", "ageMs"), payload.keySet());
        assertFalse(payload.containsKey("providerStatus"));
        assertFalse(payload.toString().contains("private prompt must not render"));
        assertFalse(payload.toString().contains("Bearer private-token"));
        assertFalse(payload.toString().contains("private.example"));
    }

    @Test
    void disabledPayloadKeepsUiHeartbeatShape() {
        Map<String, Object> payload = ChatUiHeartbeatPayload.disabled("agent_db_context_disabled");

        assertEquals(Set.of("statusBand", "reasonCode", "nextAction", "ageMs"), payload.keySet());
        assertEquals("WARN", payload.get("statusBand"));
        assertEquals("heartbeat_unavailable", payload.get("reasonCode"));
        assertEquals("retry_heartbeat", payload.get("nextAction"));
        assertEquals(0L, payload.get("ageMs"));
        assertFalse(payload.toString().contains("agent_db_context_disabled"));
    }

    @Test
    void optionalLanesStaySeparateFromCoreAndAreSanitized() throws Exception {
        Map<String, Object> lane = new LinkedHashMap<>();
        lane.put("lane", "agentDbContext");
        lane.put("status", "WARN");
        lane.put("reason", "agent_db_context_disabled");
        lane.put("nextAction", "enable_agent_db_context_for_full_pipeline_health");
        lane.put("providerUrl", "http://PRIVATE_SENTINEL_LANE@private.example/db");

        Map<String, Object> payload = ChatUiHeartbeatPayload.from(Map.of(
                "status", "OK",
                "optionalLanes", List.of(lane)));
        String json = OBJECT_MAPPER.writeValueAsString(payload);

        assertEquals("OK", payload.get("statusBand"));
        assertEquals("ready", payload.get("reasonCode"));
        assertEquals("none", payload.get("nextAction"));

        List<?> lanes = (List<?>) payload.get("optionalLanes");
        assertEquals(1, lanes.size());
        assertEquals(Set.of("lane", "status", "reason", "nextAction"),
                ((Map<?, ?>) lanes.get(0)).keySet());
        assertEquals("agentDbContext", ((Map<?, ?>) lanes.get(0)).get("lane"));
        assertEquals("WARN", ((Map<?, ?>) lanes.get(0)).get("status"));
        assertEquals("agent_db_context_disabled", ((Map<?, ?>) lanes.get(0)).get("reason"));
        assertFalse(json.contains("providerUrl"));
        assertFalse(json.contains("PRIVATE_SENTINEL_LANE"));
    }

    @Test
    void publicHeartbeatProjectsOnlyUiAllowlistedHarmonyAndTraceMemoryFields() throws Exception {
        Map<String, Object> harmony = new LinkedHashMap<>();
        harmony.put("status", "WARN");
        harmony.put("latestReason", "chat_harmony_observed");
        harmony.put("latestDegraded", true);
        harmony.put("latestAgentVisible", true);
        harmony.put("latestDebugAction", "inspect_chat_harmony_trace");
        harmony.put("latestDebugReason", "weighted_score_low");
        harmony.put("latestTraceRoute", "/api/diagnostics/trace/snapshots/latest-harmony/html");
        harmony.put("latestTraceId", "PRIVATE_SENTINEL_HARMONY_ID");
        harmony.put("rawAnswer", "PRIVATE_SENTINEL_HARMONY_ANSWER");
        harmony.put("prompt", "PRIVATE_SENTINEL_HARMONY_PROMPT");

        Map<String, Object> traceMemory = new LinkedHashMap<>();
        traceMemory.put("status", "WARN");
        traceMemory.put("latestStage", "second_refinement");
        traceMemory.put("latestPhase", "post_load");
        traceMemory.put("latestVirtualCheckpointKey", "second_refinement.post");
        traceMemory.put("latestVirtualCheckpointStage", "second_refinement");
        traceMemory.put("latestVirtualCheckpointPhase", "post_load");
        traceMemory.put("latestCheckpointHistorySize", 4L);
        traceMemory.put("latestFingerprintChanged", true);
        traceMemory.put("latestTriggered", true);
        traceMemory.put("latestRecoveryAction", "quarantine_retry_failsoft");
        traceMemory.put("latestRecoveryRoute", "nova_error_break_guard");
        traceMemory.put("latestRecoveryRouteDecision", "retry_failsoft");
        traceMemory.put("latestQuarantine", false);
        traceMemory.put("suspectPayloadIsolated", true);
        traceMemory.put("latestRisk", "high");
        traceMemory.put("latestCfvmOffered", true);
        traceMemory.put("latestCfvmPatternId", 7L);
        traceMemory.put("latestTraceRoute", "/api/diagnostics/trace/snapshots/latest-trace-memory/html");
        traceMemory.put("latestCheckpointJsonRoute", "/api/diagnostics/trace/snapshots/latest-trace-memory/checkpoints");
        traceMemory.put("latestTraceId", "PRIVATE_SENTINEL_MEMORY_ID");
        traceMemory.put("memoryCtx", "PRIVATE_SENTINEL_MEMORY_CONTEXT");
        traceMemory.put("rawSnapshot", Map.of("raw", "PRIVATE_SENTINEL_RAW_SNAPSHOT"));

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("status", "OK");
        source.put("chatHarmony", harmony);
        source.put("traceMemory", traceMemory);

        Map<String, Object> payload = ChatUiHeartbeatPayload.from(source);
        String json = OBJECT_MAPPER.writeValueAsString(payload);

        assertEquals(Set.of("statusBand", "reasonCode", "nextAction", "ageMs", "chatHarmony", "traceMemory"),
                payload.keySet());
        assertEquals(Set.of("status", "latestReason", "latestDegraded", "latestAgentVisible",
                        "latestDebugAction", "latestDebugReason", "latestTraceRoute"),
                ((Map<?, ?>) payload.get("chatHarmony")).keySet());
        assertEquals(Set.of("status", "latestStage", "latestPhase", "latestVirtualCheckpointKey",
                        "latestVirtualCheckpointStage", "latestVirtualCheckpointPhase", "latestCheckpointHistorySize",
                        "latestFingerprintChanged", "latestTriggered", "latestRecoveryAction", "latestRecoveryRoute",
                        "latestRecoveryRouteDecision", "latestQuarantine", "suspectPayloadIsolated", "latestRisk",
                        "latestCfvmOffered", "latestCfvmPatternId", "latestTraceRoute", "latestCheckpointJsonRoute"),
                ((Map<?, ?>) payload.get("traceMemory")).keySet());
        assertFalse(json.contains("PRIVATE_SENTINEL"));
        assertFalse(json.contains("latestTraceId"));
        assertFalse(json.contains("rawAnswer"));
        assertFalse(json.contains("prompt"));
        assertFalse(json.contains("memoryCtx"));
        assertFalse(json.contains("rawSnapshot"));
    }

    @Test
    void cachedHeartbeatAgePreservesAllowlistedNestedDiagnostics() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("status", "OK");
        source.put("chatHarmony", Map.of("status", "OK", "latestAgentVisible", true));
        source.put("traceMemory", Map.of("status", "WARN", "latestTriggered", true));
        Map<String, Object> base = ChatUiHeartbeatPayload.from(source);

        Map<String, Object> aged = ChatUiHeartbeatPayload.withAge(base, 1_234L);

        assertEquals(Map.of("status", "OK", "latestAgentVisible", true), base.get("chatHarmony"));
        assertEquals(Map.of("status", "WARN", "latestTriggered", true), base.get("traceMemory"));
        assertEquals(Map.of("status", "OK", "latestAgentVisible", true), aged.get("chatHarmony"));
        assertEquals(Map.of("status", "WARN", "latestTriggered", true), aged.get("traceMemory"));
        assertEquals(1_234L, aged.get("ageMs"));
    }

    @Test
    void coreHeartbeatKeepsVirtualMatrixScoreAsUntrustedProbeEvidence() {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("scorecard", Map.of(
                "historyComparisonComparable", false,
                "historyComparisonReason", "sampling_policy_mismatch",
                "currentWindowMs", 300_000L,
                "previousWindowMs", 60_000L,
                "currentSampleLimit", 80,
                "previousSampleLimit", 10));
        compact.put("tiles", List.of());
        compact.put("planUsage", List.of());
        compact.put("totalEvents", 1L);
        compact.put("warnEvents", 0L);
        compact.put("errorEvents", 1L);
        compact.put("virtualMatrixCount", 300);
        compact.put("virtualMatrixChunkCount", 30);
        compact.put("virtualMatrixWeightedScore", 0.225d);
        compact.put("virtualMatrixScoreRole", "evidence");
        compact.put("virtualMatrixScoreTrusted", false);
        compact.put("virtualMatrixDecision", "probe_required");
        compact.put("virtualMatrixActionAllowed", false);
        compact.put("virtualMatrixHotChunks", List.of());
        DebugAiMetricsService service = new DebugAiMetricsService((DebugEventStore) null) {
            @Override
            public Map<String, Object> compactSnapshot(int limit, long windowMs) {
                return compact;
            }
        };

        Map<String, Object> heartbeat = ChatUiCoreHeartbeatProbe.snapshot(
                "unit_test",
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(service),
                provider(null));
        Map<?, ?> metrics = (Map<?, ?>) heartbeat.get("debugAiMetrics");

        assertEquals("evidence", metrics.get("virtualMatrixScoreRole"));
        assertEquals(false, metrics.get("virtualMatrixScoreTrusted"));
        assertEquals("probe_required", metrics.get("virtualMatrixDecision"));
        assertEquals(false, metrics.get("virtualMatrixActionAllowed"));
        assertEquals(false, metrics.get("historyComparisonComparable"));
        assertEquals("sampling_policy_mismatch", metrics.get("historyComparisonReason"));
        assertEquals(300_000L, metrics.get("currentWindowMs"));
        assertEquals(60_000L, metrics.get("previousWindowMs"));
        assertEquals(80L, metrics.get("currentSampleLimit"));
        assertEquals(10L, metrics.get("previousSampleLimit"));
    }

    @Test
    void coreHeartbeatProjectsTraceMemoryVirtualCheckpointFromSnapshotStore() {
        TraceSnapshotStore store = enabledTraceSnapshotStore();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceMemory.triggered", true);
        trace.put("traceMemory.trigger.reason", "loader_starvation");
        trace.put("traceMemory.recovery.action", "quarantine_retry_failsoft");
        trace.put("traceMemory.recovery.route", "nova_error_break_guard");
        trace.put("traceMemory.recovery.routeDecision", "retry_failsoft");
        trace.put("traceMemory.checkpoint.stage", "first_refinement");
        trace.put("traceMemory.checkpoint.phase", "raw_snapshot");
        trace.put("traceMemory.virtualCheckpoint.latestKey", "second_refinement.post");
        trace.put("traceMemory.virtualCheckpoint.latestStage", "second_refinement");
        trace.put("traceMemory.virtualCheckpoint.latestPhase", "post_load");
        trace.put("traceMemory.fingerprint.current", "hash:safe-trace-memory-fingerprint");

        String id = store.captureCustom("trace_memory_checkpoint", "POST", "/api/chat", 200, null, trace, null);

        assertNotNull(id);
        Map<String, Object> heartbeat = ChatUiCoreHeartbeatProbe.snapshot(
                "unit_test",
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(store));
        Map<?, ?> memory = (Map<?, ?>) heartbeat.get("traceMemory");
        assertEquals("second_refinement.post", memory.get("latestVirtualCheckpointKey"));
        assertEquals("second_refinement", memory.get("latestVirtualCheckpointStage"));
        assertEquals("post_load", memory.get("latestVirtualCheckpointPhase"));
        assertEquals("/api/diagnostics/trace/snapshots/latest-trace-memory/checkpoints",
                memory.get("latestCheckpointJsonRoute"));
        assertFalse(String.valueOf(memory).contains("/api/chat"));
    }

    @Test
    void coreHeartbeatProjectsQueryRewriteCoverageFromSnapshotStoreWithoutRawQueryText() {
        TraceSnapshotStore store = enabledTraceSnapshotStore();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("web.query.rewrite.temperatureProfile", "balanced");
        trace.put("web.query.rewrite.validationTemperature", 0.15d);
        trace.put("web.query.rewrite.explorationTemperature", 0.7d);
        trace.put("web.query.rewrite.explorationRate", 0.35d);
        trace.put("web.query.rewrite.verificationLaneCount", 2);
        trace.put("web.query.rewrite.explorationLaneCount", 3);
        trace.put("web.query.rewrite.laneLabels",
                List.of("verification:official_source", "exploration:latest_update"));
        trace.put("web.query.rewrite.laneSummary", "verification:official_source|exploration:latest_update");
        trace.put("web.query.rewrite.variantLaneTemperatureHints",
                List.of("0:verification:official_source@0.15#a1b2c3d4e5f6",
                        "1:exploration:latest_update@0.7#f6e5d4c3b2a1"));
        trace.put("queryTransformer.subQueries.superTokens.enabled", true);
        trace.put("queryTransformer.subQueries.superTokens.branchCount", 3);
        trace.put("queryTransformer.subQueries.superTokens.axisCount", 3);
        trace.put("queryTransformer.subQueries.superTokens.axes", List.of("definition", "alias", "relation"));
        trace.put("queryTransformer.subQueries.superTokens.coverageComplete", true);
        trace.put("web.query.rewrite.rawQuery", "private rewrite query ownerToken=secret");

        String id = store.captureCustom("chat.trace_html.final", "POST", "/api/chat/stream", 200, null, trace, null);

        assertNotNull(id);
        Map<String, Object> heartbeat = ChatUiCoreHeartbeatProbe.snapshot(
                "unit_test",
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(store));
        Map<?, ?> queryRewrite = (Map<?, ?>) heartbeat.get("queryRewrite");

        assertEquals("OK", queryRewrite.get("status"));
        assertEquals(3L, queryRewrite.get("branchCount"));
        assertEquals(3L, queryRewrite.get("axisCount"));
        assertEquals(true, queryRewrite.get("coverageComplete"));
        assertEquals(2L, queryRewrite.get("verificationLaneCount"));
        assertEquals(3L, queryRewrite.get("explorationLaneCount"));
        assertEquals("balanced", queryRewrite.get("temperatureProfile"));
        assertEquals(0.15d, (Double) queryRewrite.get("validationTemperature"));
        assertEquals(0.7d, (Double) queryRewrite.get("explorationTemperature"));
        assertEquals("definition,alias,relation", queryRewrite.get("axisSummary"));
        assertEquals("verification:official_source|exploration:latest_update", queryRewrite.get("laneSummary"));
        assertEquals(List.of(
                "0:verification:official_source@0.15#a1b2c3d4e5f6",
                "1:exploration:latest_update@0.7#f6e5d4c3b2a1"),
                queryRewrite.get("variantLaneTemperatureHints"));
        assertFalse(String.valueOf(queryRewrite).contains("private rewrite query"));
        assertFalse(String.valueOf(queryRewrite).contains("ownerToken"));
    }

    @Test
    void coreHeartbeatRecordsProviderSuppressionBreadcrumbWithoutRawErrorText() {
        Map<String, Object> heartbeat = ChatUiCoreHeartbeatProbe.snapshot(
                "unit_test",
                provider(null),
                throwingProvider("private heartbeat failure should not leak"),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null));

        assertEquals("WARN", heartbeat.get("status"));
        assertEquals(Boolean.TRUE, TraceStore.get("chatUiHeartbeat.suppressed"));
        assertEquals("beanPresent", TraceStore.get("chatUiHeartbeat.suppressed.stage"));
        assertEquals(Boolean.TRUE, TraceStore.get("chatUiHeartbeat.suppressed.beanPresent"));
        assertEquals("IllegalStateException",
                TraceStore.get("chatUiHeartbeat.suppressed.beanPresent.errorType"));
        assertTrue(String.valueOf(
                TraceStore.get("chatUiHeartbeat.suppressed.beanPresent.errorHash")).startsWith("hash:"));
        assertFalse(TraceStore.getByPrefix("chatUiHeartbeat.").toString()
                .contains("private heartbeat failure should not leak"));
    }

    @Test
    void coreHeartbeatSeparatesPrimaryHybridSearchFromOptionalSupplementalMultiSearch() {
        Map<String, Object> heartbeat = ChatUiCoreHeartbeatProbe.snapshot(
                "unit_test",
                provider(null),
                provider(null),
                provider(null),
                provider(new HybridWebSearchProvider(null, null)),
                provider(null),
                provider(null),
                provider(null),
                provider(null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) heartbeat.get("webProviders");

        assertTrue(providers.stream().anyMatch(row ->
                "hybrid".equals(row.get("provider"))
                        && "OK".equals(row.get("status"))
                        && Boolean.TRUE.equals(row.get("hasKey"))));
        assertTrue(providers.stream().anyMatch(row ->
                "supplemental-multi-search".equals(row.get("provider"))
                        && "OK".equals(row.get("status"))
                        && Boolean.TRUE.equals(row.get("optional"))
                        && Integer.valueOf(0).equals(row.get("providerCount"))
                        && "supplemental_multi_search_providers_disabled".equals(row.get("disabledReason"))));
    }

    @Test
    void coreHeartbeatKeepsStaleLocalLlmSmokeHistoryAsSupportingEvidence() {
        LocalLlmSmokeHistoryDiagnosticsService staleSmokeHistory = new LocalLlmSmokeHistoryDiagnosticsService() {
            @Override
            public Map<String, Object> snapshot(int requestedLimit) {
                Map<String, Object> latest = new LinkedHashMap<>();
                latest.put("operatorAction", Map.of(
                        "triggered", true,
                        "triggerReason", "debug_trigger",
                        "failureClass", "model_blank",
                        "nextAction", "inspect_ollama_runtime_capacity",
                        "actionScore", 95,
                        "scoreDelta", 90));
                return Map.of(
                        "reportFound", true,
                        "reportStale", true,
                        "evidenceMode", "supporting_stale",
                        "latest", latest);
            }
        };

        Map<String, Object> heartbeat = ChatUiCoreHeartbeatProbe.snapshot(
                "unit_test",
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                staleSmokeHistory);

        Map<?, ?> modelRuntime = (Map<?, ?>) heartbeat.get("modelRuntime");
        assertEquals("UNKNOWN", modelRuntime.get("status"));
        assertEquals("stale_local_llm_smoke_history", modelRuntime.get("reason"));
        assertEquals(true, modelRuntime.get("localLlmSmokeHistoryStale"));
        assertEquals("supporting_stale", modelRuntime.get("localLlmSmokeHistoryEvidenceMode"));
        assertFalse(modelRuntime.containsKey("localLlmOperatorAction"));
    }

    @Test
    void coreHeartbeatPrefersRecentLocalLlmDebugEventOverStaleSmokeHistory() {
        DebugEventStore debugEventStore = new DebugEventStore();
        debugEventStore.emit(DebugProbeType.MODEL_GUARD,
                DebugEventLevel.WARN,
                "chat.localLlm.operatorAction.final",
                "[AWX][llm] Local LLM operator action observed",
                "ChatApiController.stream.final",
                Map.of(
                        "stage", "local_llm_operator_action",
                        "failureClass", "model_blank",
                        "triggerReason", "threshold_exceeded",
                        "nextAction", "inspect_ollama_runtime_capacity",
                        "actionScore", 20,
                        "scoreDelta", 0,
                        "negativeSignalCount", 2,
                        "localLlmNextAction", "inspect_ollama_runtime_capacity"),
                null);
        LocalLlmSmokeHistoryDiagnosticsService staleSmokeHistory = new LocalLlmSmokeHistoryDiagnosticsService() {
            @Override
            public Map<String, Object> snapshot(int requestedLimit) {
                Map<String, Object> latest = new LinkedHashMap<>();
                latest.put("operatorAction", Map.of(
                        "triggered", true,
                        "triggerReason", "older_debug_trigger",
                        "failureClass", "model_blank",
                        "nextAction", "stale_smoke_action",
                        "actionScore", 95,
                        "scoreDelta", 90));
                return Map.of(
                        "reportFound", true,
                        "reportStale", true,
                        "evidenceMode", "supporting_stale",
                        "latest", latest);
            }
        };

        Map<String, Object> heartbeat = ChatUiCoreHeartbeatProbe.snapshot(
                "unit_test",
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(debugEventStore),
                provider(null),
                provider(null),
                staleSmokeHistory);

        Map<?, ?> modelRuntime = (Map<?, ?>) heartbeat.get("modelRuntime");
        Map<?, ?> operatorAction = (Map<?, ?>) modelRuntime.get("localLlmOperatorAction");
        assertEquals("WARN", modelRuntime.get("status"));
        assertEquals("local_llm_operator_action", modelRuntime.get("reason"));
        assertEquals("debugEventStore", modelRuntime.get("source"));
        assertEquals("model_blank", operatorAction.get("failureClass"));
        assertEquals("threshold_exceeded", operatorAction.get("triggerReason"));
        assertEquals("inspect_ollama_runtime_capacity", operatorAction.get("nextAction"));
        assertEquals(20, operatorAction.get("actionScore"));
        assertEquals(2, operatorAction.get("negativeSignalCount"));
        assertFalse(String.valueOf(heartbeat).contains("stale_smoke_action"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void coreHeartbeatKeepsStaleLocalLlmDebugEventAsSupportingEvidence() {
        DebugEventStore debugEventStore = new DebugEventStore();
        Deque<DebugEvent> ring = (Deque<DebugEvent>) ReflectionTestUtils.getField(debugEventStore, "ring");
        assertNotNull(ring);
        Instant oldTs = Instant.now().minus(Duration.ofMinutes(31));
        ring.addFirst(new DebugEvent(
                "stale-local-llm-action",
                oldTs,
                oldTs.toEpochMilli(),
                DebugEventLevel.WARN,
                DebugProbeType.MODEL_GUARD,
                "chat.localLlm.operatorAction.final",
                "[AWX][llm] Local LLM operator action observed",
                null,
                null,
                null,
                "unit-test",
                "ChatApiController.stream.final",
                Map.of(
                        "stage", "local_llm_operator_action",
                        "failureClass", "model_blank",
                        "triggerReason", "threshold_exceeded",
                        "nextAction", "inspect_ollama_runtime_capacity",
                        "actionScore", 20,
                        "scoreDelta", 0,
                        "negativeSignalCount", 2),
                null,
                null));

        Map<String, Object> heartbeat = ChatUiCoreHeartbeatProbe.snapshot(
                "unit_test",
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(debugEventStore),
                provider(null),
                provider(null));

        Map<?, ?> modelRuntime = (Map<?, ?>) heartbeat.get("modelRuntime");
        assertEquals("UNKNOWN", modelRuntime.get("status"));
        assertEquals("stale_local_llm_debug_event", modelRuntime.get("reason"));
        assertEquals("debugEventStore", modelRuntime.get("source"));
        assertEquals(true, modelRuntime.get("localLlmDebugEventStale"));
        assertEquals("debug_event_stale", modelRuntime.get("localLlmDebugEventSuppressedReason"));
        assertFalse(modelRuntime.containsKey("localLlmOperatorAction"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void coreHeartbeatTreatsLocalLlmNativeSuccessActionAsOk() {
        DebugEventStore debugEventStore = new DebugEventStore();
        Deque<DebugEvent> ring = (Deque<DebugEvent>) ReflectionTestUtils.getField(debugEventStore, "ring");
        assertNotNull(ring);
        Instant now = Instant.now();
        ring.addFirst(new DebugEvent(
                "native-success-local-llm-action",
                now,
                now.toEpochMilli(),
                DebugEventLevel.INFO,
                DebugProbeType.MODEL_GUARD,
                "chat.localLlm.operatorAction.final",
                "[AWX][llm] Local LLM operator action observed",
                null,
                null,
                null,
                "unit-test",
                "ChatApiController.stream.final",
                Map.of(
                        "stage", "local_llm_operator_action",
                        "triggerReason", "native_success",
                        "failureClass", "none",
                        "nextAction", "none",
                        "actionScore", 0,
                        "scoreDelta", 0,
                        "negativeSignalCount", 0),
                null,
                null));

        Map<String, Object> heartbeat = ChatUiCoreHeartbeatProbe.snapshot(
                "unit_test",
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                provider(debugEventStore),
                provider(null),
                provider(null));

        Map<?, ?> modelRuntime = (Map<?, ?>) heartbeat.get("modelRuntime");
        Map<?, ?> operatorAction = (Map<?, ?>) modelRuntime.get("localLlmOperatorAction");
        assertEquals("OK", modelRuntime.get("status"));
        assertEquals("recent_local_model_success", modelRuntime.get("reason"));
        assertEquals("debugEventStore", modelRuntime.get("source"));
        assertEquals("none", operatorAction.get("failureClass"));
        assertEquals("native_success", operatorAction.get("triggerReason"));
        assertEquals("none", operatorAction.get("nextAction"));
    }

    @Test
    void coreHeartbeatTreatsGeneratedExternalSmokeWithoutExplicitTtlAsStaleAfterDefaultWindow() throws Exception {
        String generatedAt = Instant.now().minus(Duration.ofMinutes(61)).toString();
        JsonNode root = OBJECT_MAPPER.readTree("""
                {
                  "ok": true,
                  "reachable": true,
                  "generatedAt": "%s",
                  "stale": false
                }
                """.formatted(generatedAt));

        assertTrue(ChatUiCoreHeartbeatProbe.smokeStale(root));
    }

    private static TraceSnapshotStore enabledTraceSnapshotStore() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<TraceHtmlBuilder> htmlBuilderProvider = factory.getBeanProvider(TraceHtmlBuilder.class);
        TraceSnapshotStore store = new TraceSnapshotStore(htmlBuilderProvider);
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "maxValueLen", 1000);
        ReflectionTestUtils.setField(store, "maxEntries", 100);
        ReflectionTestUtils.setField(store, "allowReasonsCsv", "");
        ReflectionTestUtils.setField(store, "denyReasonsCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysMode", "any");
        ReflectionTestUtils.setField(store, "denyKeysCsv", "");
        ReflectionTestUtils.setField(store, "captureSample", 1.0d);
        ReflectionTestUtils.setField(store, "minIntervalMs", 0L);
        ReflectionTestUtils.setField(store, "maxPerTrace", 10);
        ReflectionTestUtils.setField(store, "budgetWindowMs", 600_000L);
        ReflectionTestUtils.setField(store, "httpStatusMin", 400);
        ReflectionTestUtils.setField(store, "captureHttpOnDebug", true);
        ReflectionTestUtils.setField(store, "captureHttpOnMl", true);
        ReflectionTestUtils.setField(store, "captureHttpOnOrch", true);
        ReflectionTestUtils.setField(store, "captureHttpOnException", true);
        ReflectionTestUtils.setField(store, "htmlEnabled", true);
        ReflectionTestUtils.setField(store, "htmlMaxLen", 60_000);
        return store;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }

    private static <T> ObjectProvider<T> throwingProvider(String message) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                throw new IllegalStateException(message);
            }

            @Override
            public T getIfAvailable() {
                throw new IllegalStateException(message);
            }

            @Override
            public T getIfUnique() {
                throw new IllegalStateException(message);
            }

            @Override
            public T getObject() {
                throw new IllegalStateException(message);
            }

            @Override
            public Iterator<T> iterator() {
                return List.<T>of().iterator();
            }

            @Override
            public Stream<T> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
