package com.example.lms.agent.context;

import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPipelineHealthLocalLlmOperatorActionFocusedTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void modelRuntimeExposesLocalLlmOperatorActionWithoutRawSecrets() {
        TraceStore.put("llm.model", "private-default-model-name");
        TraceStore.put("llm.localSmoke.operatorAction.triggered", true);
        TraceStore.put("llm.localSmoke.operatorAction.triggerReason", "threshold_exceeded");
        TraceStore.put("llm.localSmoke.operatorAction.failureClass", "model_blank");
        TraceStore.put("llm.localSmoke.operatorAction.nextAction", "prefer_native_ollama_route");
        TraceStore.put("llm.localSmoke.operatorAction.actionScore", 100);
        TraceStore.put("llm.localSmoke.operatorAction.scoreDelta", 85);
        TraceStore.put("llm.localSmoke.operatorAction.negativeSignalCount", 4);
        TraceStore.put("llm.localSmoke.operatorAction.rawSecret", "ownerToken=private-token");

        AgentPipelineHealthController controller =
                new AgentPipelineHealthController(new StaticAgentDbContextProvider());

        Map<String, Object> modelRuntime = map(controller.pipelineHealth().get("modelRuntime"));
        Map<String, Object> operatorAction = map(modelRuntime.get("localLlmOperatorAction"));

        assertEquals(Boolean.TRUE, operatorAction.get("triggered"));
        assertEquals("threshold_exceeded", operatorAction.get("triggerReason"));
        assertEquals("model_blank", operatorAction.get("failureClass"));
        assertEquals("prefer_native_ollama_route", operatorAction.get("nextAction"));
        assertEquals(100, operatorAction.get("actionScore"));
        assertEquals(85, operatorAction.get("scoreDelta"));
        assertEquals(4, operatorAction.get("negativeSignalCount"));
        assertFalse(modelRuntime.toString().contains("private-default-model-name"));
        assertFalse(modelRuntime.toString().contains("ownerToken"));
        assertFalse(modelRuntime.toString().contains("private-token"));
        assertFalse(modelRuntime.toString().contains("rawSecret"));
    }

    @Test
    void modelRuntimeExposesLocalLlmSmokeHistoryOperatorActionAcrossRequests() {
        LocalLlmSmokeHistoryDiagnosticsService smokeHistory =
                mock(LocalLlmSmokeHistoryDiagnosticsService.class);
        when(smokeHistory.snapshot(1)).thenReturn(Map.of(
                "latest", Map.of(
                        "operatorAction", Map.of(
                                "triggered", true,
                                "triggerReason", "threshold_exceeded",
                                "failureClass", "model_blank",
                                "nextAction", "prefer_native_ollama_route",
                                "actionScore", 100,
                                "scoreDelta", 85,
                                "negativeSignalCount", 4,
                                "rawSecret", "ownerToken=private-token"))));

        AgentPipelineHealthController controller =
                new AgentPipelineHealthController(new StaticAgentDbContextProvider());
        ReflectionTestUtils.setField(controller, "localLlmSmokeHistoryDiagnosticsService", smokeHistory);

        Map<String, Object> modelRuntime = map(controller.pipelineHealth().get("modelRuntime"));
        Map<String, Object> operatorAction = map(modelRuntime.get("localLlmOperatorAction"));

        assertEquals("WARN", modelRuntime.get("status"));
        assertEquals("local_llm_operator_action", modelRuntime.get("reason"));
        assertEquals("model_blank", operatorAction.get("failureClass"));
        assertEquals("prefer_native_ollama_route", operatorAction.get("nextAction"));
        assertEquals(100, operatorAction.get("actionScore"));
        assertFalse(modelRuntime.toString().contains("ownerToken"));
        assertFalse(modelRuntime.toString().contains("private-token"));
        assertFalse(modelRuntime.toString().contains("rawSecret"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class StaticAgentDbContextProvider extends AgentDbContextProvider {
        private StaticAgentDbContextProvider() {
            super(null, null, null, null);
        }

        @Override
        public MemorySnapshot memorySnapshot() {
            MemorySnapshot snapshot = new MemorySnapshot();
            snapshot.statusCounts.put("ACTIVE", 1L);
            return snapshot;
        }

        @Override
        public StrategySnapshot strategySnapshot() {
            StrategySnapshot snapshot = new StrategySnapshot();
            snapshot.performances = List.of();
            return snapshot;
        }

        @Override
        public LedgerSnapshot ledgerSnapshot() {
            LedgerSnapshot snapshot = new LedgerSnapshot();
            snapshot.hotspotDistribution = List.of();
            snapshot.recentFailures = List.of();
            return snapshot;
        }
    }
}
