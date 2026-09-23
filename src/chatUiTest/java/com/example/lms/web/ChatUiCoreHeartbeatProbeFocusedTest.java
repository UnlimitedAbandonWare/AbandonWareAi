package com.example.lms.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.transform.QueryTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatUiCoreHeartbeatProbeFocusedTest {
    private static final java.util.Set<String> PROVIDER_STATUS_FIELDS = java.util.Set.of(
            "provider", "route", "model", "enabled", "credentialPresent", "attemptCount",
            "statusCode", "latencyMs", "cacheHit", "quotaDecision", "fallbackReason", "errorClass");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void smokeStaleFallsBackToGeneratedAtAndStaleAfterMinutes() throws Exception {
        var staleWithoutFlag = OBJECT_MAPPER.readTree("""
                {
                  "generatedAt": "%s",
                  "staleAfterMinutes": 60
                }
                """.formatted(Instant.now().minus(Duration.ofMinutes(90))));
        var freshWithoutFlag = OBJECT_MAPPER.readTree("""
                {
                  "generatedAt": "%s",
                  "staleAfterMinutes": 60
                }
                """.formatted(Instant.now().minus(Duration.ofMinutes(5))));
        var oldExplicitFresh = OBJECT_MAPPER.readTree("""
                {
                  "generatedAt": "%s",
                  "staleAfterMinutes": 60,
                  "stale": false
                }
                """.formatted(Instant.now().minus(Duration.ofMinutes(90))));

        assertTrue(ChatUiCoreHeartbeatProbe.smokeStale(staleWithoutFlag));
        assertFalse(ChatUiCoreHeartbeatProbe.smokeStale(freshWithoutFlag));
        assertTrue(ChatUiCoreHeartbeatProbe.smokeStale(oldExplicitFresh));
    }

    @Test
    void goalNextTopActionsStayStructuredAndBounded() throws Exception {
        var summary = OBJECT_MAPPER.readTree("""
                {
                  "topActions": [
                    {"source": "browser_use", "action": "open_public_80_443_then_rerun_browser_public_domain_ui_smoke", "decision": "evidence_needed"},
                    {"source": "supabase_apply", "action": "set_SUPABASE_PROJECT_REF", "decision": "evidence_needed"},
                    {"source": "supabase_apply", "action": "complete_supabase_mcp_oauth_flow", "decision": "evidence_needed"},
                    {"source": "supabase_apply", "action": "install_supabase_cli_or_use_mcp_execute_sql", "decision": "evidence_needed"}
                  ]
                }
                """);

        var topActions = ChatUiCoreHeartbeatProbe.topActions(summary, 3);

        assertEquals(3, topActions.size());
        assertEquals("browser_use", topActions.get(0).get("source"));
        assertEquals("open_public_80_443_then_rerun_browser_public_domain_ui_smoke", topActions.get(0).get("action"));
        assertEquals("set_SUPABASE_PROJECT_REF", topActions.get(1).get("action"));
        assertEquals("complete_supabase_mcp_oauth_flow", topActions.get(2).get("action"));
    }

    @Test
    void desktopOnlyCoreFallbackKeepsExternalEvidenceOptional() {
        Map<String, Object> snapshot = ChatUiCoreHeartbeatProbe.snapshot(
                "agent_db_context_disabled",
                provider(null),
                provider(mock(PromptBuilder.class)),
                provider(mock(QueryTransformer.class)),
                provider(mock(HybridWebSearchProvider.class)),
                provider(null),
                provider(null),
                provider(null),
                provider(null));

        assertEquals("OK", snapshot.get("status"));
        assertEquals("supporting_external_evidence_missing", snapshot.get("reason"));
        assertEquals("none_for_desktop_only", snapshot.get("nextAction"));
        assertEquals("desktop_only_ready", snapshot.get("decision"));
        assertEquals("optional", snapshot.get("externalEvidenceMode"));
        assertEquals(false, snapshot.get("requireProducerBundles"));
        assertEquals(false, snapshot.get("supabaseLiveProofRequired"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> externalEvidence =
                (List<Map<String, Object>>) snapshot.get("externalEvidence");
        Map<String, Object> supabase = externalEvidence.stream()
                .filter(row -> "supabase".equals(row.get("service")))
                .findFirst()
                .orElseThrow();
        assertEquals("WARN", supabase.get("status"));
        assertEquals("supabase_project_scope_or_auth_unverified", supabase.get("evidenceNeeded"));
        assertEquals("run_readonly_supabase_context_probe", supabase.get("nextAction"));

        List<Map<String, Object>> lanes = mapList(snapshot.get("optionalLanes"));
        assertEquals(1, lanes.size());
        Map<String, Object> lane = lanes.get(0);
        assertEquals("agentDbContext", lane.get("lane"));
        assertEquals("WARN", lane.get("status"));
        assertEquals("agent_db_context_disabled", lane.get("reason"));
    }

    @Test
    void fallbackHeartbeatPublishesHonestUnavailableProviderRows() {
        Map<String, Object> snapshot = ChatUiCoreHeartbeatProbe.snapshot(
                "agent_db_context_disabled", null, null, null, null, null, null, null, null);

        List<Map<String, Object>> rows = mapList(snapshot.get("providerStatus"));
        assertEquals(List.of("brave", "naver", "gemini"),
                rows.stream().map(row -> String.valueOf(row.get("provider"))).toList());
        for (Map<String, Object> row : rows) {
            assertEquals(PROVIDER_STATUS_FIELDS, row.keySet());
            assertEquals(0, row.get("attemptCount"));
            assertEquals("not_observed", row.get("statusCode"));
            assertEquals(0L, row.get("latencyMs"));
            assertEquals("not_observed", row.get("quotaDecision"));
            assertEquals("not_observed", row.get("errorClass"));
        }
    }

    @Test
    void fallbackHeartbeatReadsOnlyTheExactObservedProviderFields() {
        TraceStore.put("provider.status.gemini.provider", "gemini");
        TraceStore.put("provider.status.gemini.route", "search-expansion");
        TraceStore.put("provider.status.gemini.model", "gemini-2.5-flash");
        TraceStore.put("provider.status.gemini.enabled", true);
        TraceStore.put("provider.status.gemini.credentialPresent", true);
        TraceStore.put("provider.status.gemini.attemptCount", 1);
        TraceStore.put("provider.status.gemini.statusCode", 200);
        TraceStore.put("provider.status.gemini.latencyMs", 23L);
        TraceStore.put("provider.status.gemini.cacheHit", false);
        TraceStore.put("provider.status.gemini.quotaDecision", "allowed");
        TraceStore.put("provider.status.gemini.fallbackReason", "none");
        TraceStore.put("provider.status.gemini.errorClass", "none");
        TraceStore.put("provider.status.gemini.rawPrompt", "private prompt must not render");

        Map<String, Object> snapshot = ChatUiCoreHeartbeatProbe.snapshot(
                "agent_db_context_disabled", null, null, null, null, null, null, null, null);
        Map<String, Object> gemini = mapList(snapshot.get("providerStatus")).stream()
                .filter(row -> "gemini".equals(row.get("provider")))
                .findFirst()
                .orElseThrow();

        assertEquals(PROVIDER_STATUS_FIELDS, gemini.keySet());
        assertEquals(1, gemini.get("attemptCount"));
        assertEquals(200, gemini.get("statusCode"));
        assertEquals(23L, gemini.get("latencyMs"));
        assertFalse(snapshot.toString().contains("private prompt must not render"));
    }

    @Test
    void coreFallbackHeartbeatPublishesLocalLlmOperatorActionWithoutRawSecrets() {
        TraceStore.put("llm.localSmoke.operatorAction.triggered", true);
        TraceStore.put("llm.localSmoke.operatorAction.triggerReason", "threshold_exceeded");
        TraceStore.put("llm.localSmoke.operatorAction.failureClass", "model_blank");
        TraceStore.put("llm.localSmoke.operatorAction.nextAction", "prefer_native_ollama_route");
        TraceStore.put("llm.localSmoke.operatorAction.actionScore", 100);
        TraceStore.put("llm.localSmoke.operatorAction.scoreDelta", 85);
        TraceStore.put("llm.localSmoke.operatorAction.negativeSignalCount", 4);
        TraceStore.put("llm.localSmoke.operatorAction.upstreamStatus", 500);
        TraceStore.put("llm.localSmoke.operatorAction.upstreamFailureClass", "ollama_upstream_5xx");
        TraceStore.put("llm.localSmoke.operatorAction.upstreamNextAction", "inspect_ollama_runtime_capacity");
        TraceStore.put("llm.localSmoke.operatorAction.rawSecret", "ownerToken=private-token");

        Map<String, Object> snapshot = ChatUiCoreHeartbeatProbe.snapshot(
                "agent_db_context_disabled", null, null, null, null, null, null, null, null);
        Map<String, Object> modelRuntime = map(snapshot.get("modelRuntime"));
        Map<String, Object> operatorAction = map(modelRuntime.get("localLlmOperatorAction"));

        assertEquals("WARN", modelRuntime.get("status"));
        assertEquals("local_llm_operator_action", modelRuntime.get("reason"));
        assertEquals(Boolean.TRUE, operatorAction.get("triggered"));
        assertEquals("threshold_exceeded", operatorAction.get("triggerReason"));
        assertEquals("model_blank", operatorAction.get("failureClass"));
        assertEquals("inspect_ollama_runtime_capacity", operatorAction.get("nextAction"));
        assertEquals(100, operatorAction.get("actionScore"));
        assertEquals(85, operatorAction.get("scoreDelta"));
        assertEquals(4, operatorAction.get("negativeSignalCount"));
        assertEquals(500, operatorAction.get("upstreamStatus"));
        assertEquals("ollama_upstream_5xx", operatorAction.get("upstreamFailureClass"));
        assertEquals("inspect_ollama_runtime_capacity", operatorAction.get("upstreamNextAction"));
        assertFalse(modelRuntime.toString().contains("ownerToken"));
        assertFalse(modelRuntime.toString().contains("private-token"));
        assertFalse(modelRuntime.toString().contains("rawSecret"));
    }

    @Test
    void coreFallbackHeartbeatPublishesLocalLlmSmokeHistoryOperatorActionAcrossRequests() {
        LocalLlmSmokeHistoryDiagnosticsService smokeHistory =
                mock(LocalLlmSmokeHistoryDiagnosticsService.class);
        when(smokeHistory.snapshot(1)).thenReturn(Map.of(
                "latest", Map.of(
                        "operatorAction", Map.ofEntries(
                                Map.entry("triggered", true),
                                Map.entry("triggerReason", "threshold_exceeded"),
                                Map.entry("failureClass", "model_blank"),
                                Map.entry("nextAction", "prefer_native_ollama_route"),
                                Map.entry("actionScore", 100),
                                Map.entry("scoreDelta", 85),
                                Map.entry("negativeSignalCount", 4),
                                Map.entry("upstreamStatus", 500),
                                Map.entry("upstreamFailureClass", "ollama_upstream_5xx"),
                                Map.entry("upstreamNextAction", "inspect_ollama_runtime_capacity"),
                                Map.entry("rawSecret", "ownerToken=private-token")))));

        Map<String, Object> snapshot = ChatUiCoreHeartbeatProbe.snapshot(
                "agent_db_context_disabled", null, null, null, null, null, null, null, null,
                smokeHistory);
        Map<String, Object> modelRuntime = map(snapshot.get("modelRuntime"));
        Map<String, Object> operatorAction = map(modelRuntime.get("localLlmOperatorAction"));

        assertEquals("WARN", modelRuntime.get("status"));
        assertEquals("local_llm_operator_action", modelRuntime.get("reason"));
        assertEquals("localLlmSmokeHistory", modelRuntime.get("source"));
        assertEquals("model_blank", operatorAction.get("failureClass"));
        assertEquals("inspect_ollama_runtime_capacity", operatorAction.get("nextAction"));
        assertEquals(100, operatorAction.get("actionScore"));
        assertEquals(500, operatorAction.get("upstreamStatus"));
        assertEquals("ollama_upstream_5xx", operatorAction.get("upstreamFailureClass"));
        assertEquals("inspect_ollama_runtime_capacity", operatorAction.get("upstreamNextAction"));
        assertFalse(modelRuntime.toString().contains("ownerToken"));
        assertFalse(modelRuntime.toString().contains("private-token"));
        assertFalse(modelRuntime.toString().contains("rawSecret"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.of();
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
}
