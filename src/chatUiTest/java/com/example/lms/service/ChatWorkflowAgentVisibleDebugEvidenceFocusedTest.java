package com.example.lms.service;

import dev.langchain4j.data.document.Document;
import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowAgentVisibleDebugEvidenceFocusedTest {

    @Test
    void directDebugFallbackKeepsLocalLlmUpstreamActionVisible() throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod("composeAgentVisibleDebugFallback", List.class);
        method.setAccessible(true);

        String heartbeat = String.join("\n",
                "AGENT_VISIBLE_DEBUG_HEARTBEAT",
                "summary=browser:OK computer-use:WARN supabase:supabase_project_scope_or_auth_unverified matrix:300/30 decision:investigate_hot_chunk",
                "external.browser.status=OK",
                "external.browser.evidenceNeeded=none",
                "external.browser.stale=false",
                "external.computer-use.status=WARN",
                "external.computer-use.evidenceNeeded=computer_use_smoke_stale",
                "external.computer-use.stale=true",
                "external.supabase.evidenceNeeded=supabase_project_scope_or_auth_unverified",
                "agentDbContext.status=DISABLED",
                "agentDbContext.reason=agent_db_context_disabled",
                "agentDbContext.nextAction=enable_agent_db_context_for_full_pipeline_health",
                "localLlm.operatorAction.failureClass=model_blank",
                "localLlm.operatorAction.nextAction=inspect_ollama_runtime_capacity",
                "localLlm.operatorAction.upstreamStatus=500",
                "localLlm.operatorAction.upstreamFailureClass=ollama_upstream_5xx",
                "localLlm.operatorAction.upstreamNextAction=inspect_ollama_runtime_capacity",
                "debug.ai.metrics.virtualMatrix.count=300",
                "debug.ai.metrics.virtualMatrix.chunkCount=30",
                "debug.ai.metrics.virtualMatrix.decision=investigate_hot_chunk");

        String answer = (String) method.invoke(null, List.of(Document.from(heartbeat)));

        assertTrue(answer.contains("LLM upstream: ollama_upstream_5xx"),
                "fallback answer should expose the upstream LLM failure class");
        assertTrue(answer.contains("status=500"),
                "fallback answer should expose the redacted upstream HTTP status");
        assertTrue(answer.contains("next=inspect_ollama_runtime_capacity"),
                "fallback answer should expose the next operator action");
        assertTrue(answer.contains("DB context: DISABLED"),
                "fallback answer should expose agent DB context availability");
        assertTrue(answer.contains("reason=agent_db_context_disabled"),
                "fallback answer should expose the DB context disabled reason");
        assertTrue(answer.contains("next=enable_agent_db_context_for_full_pipeline_health"),
                "fallback answer should expose the DB context next action");
    }

    @Test
    void agentDebugMirrorPullsLatestLocalLlmSmokeHistoryIntoTraceStore() {
        TraceStore.clear();

        ChatWorkflow.mirrorLocalLlmSmokeHistoryOperatorActionForAgentDebug(new FakeLocalLlmSmokeHistory());

        assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.localSmoke.operatorAction.triggered")));
        assertTrue("model_blank".equals(TraceStore.get("llm.localSmoke.operatorAction.failureClass")));
        assertTrue("inspect_ollama_runtime_capacity".equals(
                TraceStore.get("llm.localSmoke.operatorAction.nextAction")));
        assertTrue(Integer.valueOf(500).equals(TraceStore.get("llm.localSmoke.operatorAction.upstreamStatus")));
        assertTrue("ollama_upstream_5xx".equals(
                TraceStore.get("llm.localSmoke.operatorAction.upstreamFailureClass")));
        assertTrue("inspect_ollama_runtime_capacity".equals(
                TraceStore.get("llm.localSmoke.operatorAction.upstreamNextAction")));
        assertTrue(TraceStore.get("llm.localSmoke.operatorAction.rawSecret") == null);
    }

    @Test
    void agentDebugMirrorMarksDbContextDisabledWhenPipelineHealthIsMissing() {
        TraceStore.clear();

        ChatWorkflow.mirrorAgentDbContextAvailabilityForAgentDebug(null);

        assertTrue("DISABLED".equals(TraceStore.get("agent.dbContext.agentVisible.status")));
        assertTrue("agent_db_context_disabled".equals(TraceStore.get("agent.dbContext.agentVisible.reason")));
        assertTrue("enable_agent_db_context_for_full_pipeline_health".equals(
                TraceStore.get("agent.dbContext.agentVisible.nextAction")));
    }

    private static final class FakeLocalLlmSmokeHistory extends LocalLlmSmokeHistoryDiagnosticsService {
        @Override
        public Map<String, Object> snapshot(int requestedLimit) {
            return Map.of("latest", Map.of("operatorAction", Map.ofEntries(
                    Map.entry("triggered", true),
                    Map.entry("triggerReason", "threshold_exceeded"),
                    Map.entry("failureClass", "model_blank"),
                    Map.entry("nextAction", "inspect_ollama_runtime_capacity"),
                    Map.entry("actionScore", 20),
                    Map.entry("scoreDelta", 0),
                    Map.entry("negativeSignalCount", 2),
                    Map.entry("upstreamStatus", 500),
                    Map.entry("upstreamFailureClass", "ollama_upstream_5xx"),
                    Map.entry("upstreamNextAction", "inspect_ollama_runtime_capacity"),
                    Map.entry("rawSecret", "ownerToken=private-token"))));
        }
    }
}
