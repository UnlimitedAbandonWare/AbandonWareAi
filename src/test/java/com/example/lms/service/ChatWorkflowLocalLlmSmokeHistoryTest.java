package com.example.lms.service;

import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatWorkflowLocalLlmSmokeHistoryTest {

    @BeforeEach
    void clearTraceBefore() {
        TraceStore.clear();
    }

    @AfterEach
    void clearTraceAfter() {
        TraceStore.clear();
    }

    @Test
    void staleSmokeReportDoesNotPromoteModelBlankAsCurrentRequestFailure() {
        LocalLlmSmokeHistoryDiagnosticsService staleSmoke = new LocalLlmSmokeHistoryDiagnosticsService() {
            @Override
            public Map<String, Object> snapshot(int requestedLimit) {
                return Map.of(
                        "reportFound", true,
                        "reportStale", true,
                        "evidenceMode", "supporting_stale",
                        "latest", Map.of("operatorAction", Map.of(
                                "triggered", true,
                                "triggerReason", "threshold_exceeded",
                                "failureClass", "model_blank",
                                "nextAction", "prefer_native_ollama_route",
                                "actionScore", 100)));
            }
        };

        ChatWorkflow.mirrorLocalLlmSmokeHistoryOperatorActionForAgentDebug(staleSmoke);

        assertNull(TraceStore.get("llm.localSmoke.operatorAction.failureClass"));
        assertNull(TraceStore.get("llm.localSmoke.operatorAction.triggered"));
        assertEquals(Boolean.TRUE, TraceStore.get("llm.localSmoke.operatorAction.stale"));
        assertEquals("supporting_stale", TraceStore.get("llm.localSmoke.operatorAction.evidenceMode"));
    }
}
