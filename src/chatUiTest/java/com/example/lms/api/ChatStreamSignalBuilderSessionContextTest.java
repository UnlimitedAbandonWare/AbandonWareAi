package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Session 56 cycle-02: 세션 컨텍스트 주입 상태와 Trace Memory stage 관측/합성 구분이
 * DebugFx 라벨로 노출되는지 검증한다.
 */
class ChatStreamSignalBuilderSessionContextTest {

    @Test
    void debugFxSurfacesPromptContextInjectionLabels() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.ofEntries(
                        Map.entry("prompt.contextInjected.history", true),
                        Map.entry("prompt.contextInjected.historyChars", 214),
                        Map.entry("prompt.contextInjected.lastAssistant", true),
                        Map.entry("prompt.contextInjected.memory", false),
                        Map.entry("prompt.contextInjected.delivered", true)),
                null,
                null);

        assertEquals("true", signal.labels().get("promptContextInjectedHistory"));
        assertEquals("214", signal.labels().get("promptContextInjectedHistoryChars"));
        assertEquals("true", signal.labels().get("promptContextInjectedLastAssistant"));
        assertEquals("false", signal.labels().get("promptContextInjectedMemory"));
        assertEquals("true", signal.labels().get("promptContextInjectedDelivered"));
    }

    @Test
    void debugFxSurfacesTraceMemoryStageObservedFlag() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.ofEntries(
                        Map.entry("traceMemory.checkpoint.source", "ChatWorkflow"),
                        Map.entry("traceMemory.virtualCheckpoint.latestStage", "load"),
                        Map.entry("traceMemory.virtualCheckpoint.stageObserved", false)),
                null,
                null);

        assertEquals("ChatWorkflow", signal.labels().get("traceMemoryCheckpointSource"));
        assertEquals("load", signal.labels().get("traceMemoryVirtualCheckpointStage"));
        assertEquals("false", signal.labels().get("traceMemoryVirtualCheckpointStageObserved"));
    }

    @Test
    void debugFxReportsAgentDbContextUnobservedInsteadOfSynthesizedDisabled() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.of("chat.harmony.postprocess.agentVisible", true),
                null,
                null);

        assertEquals("UNOBSERVED", signal.labels().get("agentDbContextStatus"));
        assertEquals("agent_db_context_not_observed", signal.labels().get("agentDbContextReason"));
        assertEquals("inspect_agent_db_context_status_mirror",
                signal.labels().get("agentDbContextNextAction"));
    }

    @Test
    void debugFxKeepsObservedAgentDbContextFields() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.ofEntries(
                        Map.entry("prompt.agentDebugEvidence.agentDbContext.status", "PROBE_UNAVAILABLE"),
                        Map.entry("prompt.agentDebugEvidence.agentDbContext.reason",
                                "agent_db_context_controller_unavailable")),
                null,
                null);

        assertEquals("PROBE_UNAVAILABLE", signal.labels().get("agentDbContextStatus"));
        assertEquals("agent_db_context_controller_unavailable",
                signal.labels().get("agentDbContextReason"));
        assertNull(signal.labels().get("agentDbContextNextAction"));
    }
}
