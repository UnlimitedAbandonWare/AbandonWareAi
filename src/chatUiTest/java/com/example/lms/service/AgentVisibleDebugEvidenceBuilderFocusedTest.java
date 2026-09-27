package com.example.lms.service;

import org.junit.jupiter.api.Test;

import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.search.TraceStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.util.Map.entry;

class AgentVisibleDebugEvidenceBuilderFocusedTest {

    @Test
    void agentVisibleDebugEvidenceIncludesHotVirtualMatrixAction() {
        String text = AgentVisibleDebugEvidenceBuilder.buildEvidenceTextForTest(
                "debug matrix status",
                Map.ofEntries(
                        entry("virtualMatrixCount", 300),
                        entry("virtualMatrixChunkCount", 30),
                        entry("virtualMatrixWeightedScore", 0.481d),
                        entry("virtualMatrixDecision", "investigate_hot_chunk"),
                        entry("virtualMatrixHotChunkIndex", 17),
                        entry("virtualMatrixHotChunkRiskScore", 0.77d),
                        entry("nextDebugAction", "investigate_debug_ai_hot_chunk"),
                        entry("nextDebugReason", "investigate_hot_chunk"),
                        entry("totalEvents", 12),
                        entry("warnEvents", 4),
                        entry("errorEvents", 0),
                        entry("topTile", "EVIDENCE_OUTPUT"),
                        entry("topFailureClass", "chat_harmony.fallback_evidence")),
                Map.of("status", "OK", "evidenceNeeded", "none"),
                Map.of("status", "OK", "evidenceNeeded", "none"),
                Map.of("status", "WARN", "evidenceNeeded", "supabase_project_scope_or_auth_unverified"));

        assertTrue(text.contains("debug.ai.metrics.virtualMatrix.hotChunkIndex=17"));
        assertTrue(text.contains("debug.ai.metrics.virtualMatrix.hotChunkRiskScore=0.77"));
        assertTrue(text.contains("debug.ai.metrics.nextAction=investigate_debug_ai_hot_chunk"));
        assertTrue(text.contains("debug.ai.metrics.nextReason=investigate_hot_chunk"));
    }

    @Test
    void buildLocalDocsMirrorsHotVirtualMatrixActionIntoTraceStore() {
        TraceStore.clear();

        var docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                "debug matrix status",
                new FakeDebugAiMetricsService());

        assertEquals(1, docs.size());
        assertEquals(300, TraceStore.get("prompt.agentDebugEvidence.virtualMatrix.count"));
        assertEquals(30, TraceStore.get("prompt.agentDebugEvidence.virtualMatrix.chunkCount"));
        assertEquals(22, TraceStore.get("prompt.agentDebugEvidence.virtualMatrix.hotChunkIndex"));
        assertEquals(0.83d, TraceStore.get("prompt.agentDebugEvidence.virtualMatrix.hotChunkRiskScore"));
        assertEquals("mitigate_debug_ai_hot_chunk", TraceStore.get("prompt.agentDebugEvidence.nextAction"));
        assertEquals("mitigate_now", TraceStore.get("prompt.agentDebugEvidence.nextReason"));
    }

    @Test
    void buildLocalDocsMirrorsRedactedTurnTimelineIntoTraceStore() {
        TraceStore.clear();
        TraceStore.put("sid", "chat-session-raw-42");
        TraceStore.put("requestId", "request-raw-99");
        TraceStore.put("trace.id", "trace-raw-abc");

        var docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                "debug matrix status",
                new FakeDebugAiMetricsService());

        assertEquals(1, docs.size());
        String text = docs.get(0).text();
        assertTrue(String.valueOf(TraceStore.get("prompt.agentDebugEvidence.turn.sessionIdHash")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("prompt.agentDebugEvidence.turn.requestIdHash")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("prompt.agentDebugEvidence.turn.traceIdHash")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("prompt.agentDebugEvidence.turn.timelineKey")).startsWith("hash:"));
        assertNotEquals("chat-session-raw-42", TraceStore.get("prompt.agentDebugEvidence.turn.sessionIdHash"));
        assertNotEquals("request-raw-99", TraceStore.get("prompt.agentDebugEvidence.turn.requestIdHash"));
        assertNotEquals("trace-raw-abc", TraceStore.get("prompt.agentDebugEvidence.turn.traceIdHash"));
        assertTrue(text.contains("chat.turn.sessionIdHash=hash:"));
        assertTrue(text.contains("chat.turn.requestIdHash=hash:"));
        assertTrue(text.contains("chat.turn.traceIdHash=hash:"));
        assertTrue(text.contains("chat.turn.timelineKey=hash:"));
    }

    @Test
    void buildLocalDocsMirrorsChatHarmonyPostprocessIntoAgentVisibleEvidence() {
        TraceStore.clear();
        TraceStore.put("chat.harmony.postprocess.agentVisible", true);
        TraceStore.put("chat.harmony.postprocess.decision", "fallback_evidence");
        TraceStore.put("chat.harmony.postprocess.reason", "fallback_mode_answer");
        TraceStore.put("chat.harmony.postprocess.weightedScore", 0.42d);
        TraceStore.put("chat.harmony.postprocess.evidenceCount", 3);
        TraceStore.put("chat.harmony.rawAnswer", "raw auth marker private-token should not surface");
        TraceStore.put("chat.harmony.rawUserQuery", "raw owner marker private-token should not surface");

        var docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                "debug harmony status",
                new FakeDebugAiMetricsService());

        assertEquals(1, docs.size());
        String text = docs.get(0).text();
        assertTrue(text.contains("chat.harmony.decision=fallback_evidence"));
        assertTrue(text.contains("chat.harmony.reason=fallback_mode_answer"));
        assertTrue(text.contains("chat.harmony.weightedScore=0.42"));
        assertTrue(text.contains("chat.harmony.evidenceCount=3"));
        assertTrue(text.contains("chat.harmony.nextAction=inspect_chat_harmony_trace"));
        assertEquals("fallback_evidence", TraceStore.get("prompt.agentDebugEvidence.chatHarmony.decision"));
        assertEquals("fallback_mode_answer", TraceStore.get("prompt.agentDebugEvidence.chatHarmony.reason"));
        assertEquals(0.42d, TraceStore.get("prompt.agentDebugEvidence.chatHarmony.weightedScore"));
        assertEquals(3, TraceStore.get("prompt.agentDebugEvidence.chatHarmony.evidenceCount"));
        assertEquals("inspect_chat_harmony_trace", TraceStore.get("prompt.agentDebugEvidence.chatHarmony.nextAction"));
        assertFalse(text.contains("private-token"));
        assertFalse(String.valueOf(TraceStore.getByPrefix("prompt.agentDebugEvidence.")).contains("private-token"));
    }

    @Test
    void buildLocalDocsMirrorsExternalLanesIntoAgentVisibleTraceStore() {
        TraceStore.clear();

        var docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                "debug browser computer supabase status",
                new FakeDebugAiMetricsService());

        assertEquals(1, docs.size());
        Map<String, Object> trace = TraceStore.getByPrefix("prompt.agentDebugEvidence.external.");
        assertTrue(trace.containsKey("prompt.agentDebugEvidence.external.browser.status"), trace.toString());
        assertTrue(trace.containsKey("prompt.agentDebugEvidence.external.browser.evidenceNeeded"), trace.toString());
        assertTrue(trace.containsKey("prompt.agentDebugEvidence.external.browser.nextAction"), trace.toString());
        assertTrue(trace.containsKey("prompt.agentDebugEvidence.external.computerUse.status"), trace.toString());
        assertTrue(trace.containsKey("prompt.agentDebugEvidence.external.computerUse.evidenceNeeded"), trace.toString());
        assertTrue(trace.containsKey("prompt.agentDebugEvidence.external.computerUse.nextAction"), trace.toString());
        assertEquals("WARN", trace.get("prompt.agentDebugEvidence.external.supabase.status"));
        assertEquals("supabase_project_scope_or_auth_unverified",
                trace.get("prompt.agentDebugEvidence.external.supabase.evidenceNeeded"));
        assertEquals("authenticate_supabase_mcp_or_cli",
                trace.get("prompt.agentDebugEvidence.external.supabase.nextAction"));
        assertEquals("supabase_project_scope_or_auth_unverified",
                TraceStore.get("debug.ai.agentDebugEvidence.external.supabase.evidenceNeeded"));
        assertEquals("authenticate_supabase_mcp_or_cli",
                TraceStore.get("debug.ai.agentDebugEvidence.external.supabase.nextAction"));
        String publicTrace = trace.toString();
        assertFalse(publicTrace.contains("SUPABASE_ACCESS_TOKEN"));
        assertFalse(publicTrace.contains("Authorization"));
        assertFalse(publicTrace.contains("Cookie"));
    }

    @Test
    void buildLocalDocsMirrorsAgentDbContextAvailabilityIntoAgentVisibleEvidence() {
        TraceStore.clear();
        TraceStore.put("agent.dbContext.agentVisible.status", "DISABLED");
        TraceStore.put("agent.dbContext.agentVisible.reason", "agent_db_context_disabled");
        TraceStore.put("agent.dbContext.agentVisible.nextAction", "enable_agent_db_context_for_full_pipeline_health");
        TraceStore.put("agent.dbContext.agentVisible.rawSecret", "ownerToken=private-token");

        var docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                "debug db context status",
                new FakeDebugAiMetricsService());

        assertEquals(1, docs.size());
        String text = docs.get(0).text();
        assertTrue(text.contains("agentDbContext.status=DISABLED"));
        assertTrue(text.contains("agentDbContext.reason=agent_db_context_disabled"));
        assertTrue(text.contains("agentDbContext.nextAction=enable_agent_db_context_for_full_pipeline_health"));
        assertEquals("DISABLED", TraceStore.get("prompt.agentDebugEvidence.agentDbContext.status"));
        assertEquals("agent_db_context_disabled", TraceStore.get("prompt.agentDebugEvidence.agentDbContext.reason"));
        assertEquals("enable_agent_db_context_for_full_pipeline_health",
                TraceStore.get("debug.ai.agentDebugEvidence.agentDbContext.nextAction"));
        assertFalse(text.contains("private-token"));
        assertFalse(String.valueOf(TraceStore.getByPrefix("prompt.agentDebugEvidence.agentDbContext."))
                .contains("private-token"));
    }

    @Test
    void buildLocalDocsMirrorsLocalLlmOperatorActionIntoAgentVisibleTraceStore() {
        TraceStore.clear();
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

        var docs = AgentVisibleDebugEvidenceBuilder.buildLocalDocs(
                "debug local llm route status",
                new FakeDebugAiMetricsService());

        assertEquals(1, docs.size());
        String text = docs.get(0).text();
        assertTrue(text.contains("localLlm.operatorAction.triggered=true"));
        assertTrue(text.contains("localLlm.operatorAction.failureClass=model_blank"));
        assertTrue(text.contains("localLlm.operatorAction.nextAction=prefer_native_ollama_route"));
        assertTrue(text.contains("localLlm.operatorAction.triggerReason=threshold_exceeded"));
        assertTrue(text.contains("localLlm.operatorAction.actionScore=100"));
        assertTrue(text.contains("localLlm.operatorAction.scoreDelta=85"));
        assertTrue(text.contains("localLlm.operatorAction.negativeSignalCount=4"));
        assertTrue(text.contains("localLlm.operatorAction.upstreamStatus=500"));
        assertTrue(text.contains("localLlm.operatorAction.upstreamFailureClass=ollama_upstream_5xx"));
        assertTrue(text.contains("localLlm.operatorAction.upstreamNextAction=inspect_ollama_runtime_capacity"));
        assertEquals("model_blank",
                TraceStore.get("prompt.agentDebugEvidence.localLlm.operatorAction.failureClass"));
        assertEquals("prefer_native_ollama_route",
                TraceStore.get("prompt.agentDebugEvidence.localLlm.operatorAction.nextAction"));
        assertEquals(500,
                TraceStore.get("prompt.agentDebugEvidence.localLlm.operatorAction.upstreamStatus"));
        assertEquals("ollama_upstream_5xx",
                TraceStore.get("debug.ai.agentDebugEvidence.localLlm.operatorAction.upstreamFailureClass"));
        assertEquals("threshold_exceeded",
                TraceStore.get("debug.ai.agentDebugEvidence.localLlm.operatorAction.triggerReason"));
        assertFalse(text.contains("private-token"));
        assertFalse(String.valueOf(TraceStore.getByPrefix("prompt.agentDebugEvidence.localLlm."))
                .contains("private-token"));
    }

    private static final class FakeDebugAiMetricsService extends DebugAiMetricsService {
        private FakeDebugAiMetricsService() {
            super(null);
        }

        @Override
        public Map<String, Object> compactSnapshot(int limit, long windowMs) {
            return Map.ofEntries(
                    entry("virtualMatrixCount", 300),
                    entry("virtualMatrixChunkCount", 30),
                    entry("virtualMatrixWeightedScore", 0.83d),
                    entry("virtualMatrixDecision", "mitigate_now"),
                    entry("virtualMatrixHotChunks", List.of(Map.of(
                            "chunkIndex", 22,
                            "riskScore", 0.83d))),
                    entry("totalEvents", 18),
                    entry("warnEvents", 6),
                    entry("errorEvents", 1),
                    entry("scorecard", Map.of(
                            "hotTile", "EVIDENCE_OUTPUT",
                            "anomalyFailureClass", "chat_harmony.blank_guard")));
        }
    }
}
