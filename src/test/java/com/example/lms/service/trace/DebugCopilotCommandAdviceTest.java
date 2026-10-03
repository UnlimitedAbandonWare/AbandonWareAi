package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugCopilotCommandAdviceTest {
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        MDC.clear();
    }

    @Test
    void recommendsExistingCorrelatedReadToolWithoutAnUnverifiedShellPipeline() {
        String rawTrace = "diagnostic-request-42";
        MDC.put("traceId", rawTrace);
        TraceStore.put("dbg.search.enabled", true);

        new DebugCopilotService().maybeEnrichTrace();

        Object rawAdvice = TraceStore.get("dbg.copilot.commandAdvice");
        assertTrue(rawAdvice instanceof List<?>);
        Map<?, ?> advice = (Map<?, ?>) ((List<?>) rawAdvice).get(0);
        assertEquals("debug.trace.lookup", advice.get("toolId"));
        assertEquals("java_agent_tool", advice.get("providerSurface"));
        assertEquals("none", advice.get("shell"));
        Map<?, ?> arguments = (Map<?, ?>) advice.get("arguments");
        assertEquals("correlated", arguments.get("mode"));
        assertEquals(SafeRedactor.hashValue(rawTrace), arguments.get("traceIdHash"));
        String output = String.valueOf(TraceStore.get("dbg.copilot.actions"));
        assertFalse(output.contains("tail -n"));
        assertFalse(output.contains("export TRACE_ID_HASH"));
        assertFalse(output.contains(rawTrace));
    }

    @Test
    void absentTraceIdIsMarkedUnavailableInsteadOfSupplyingAPlaceholderCommand() {
        TraceStore.put("dbg.search.enabled", true);

        new DebugCopilotService().maybeEnrichTrace();

        Object rawAdvice = TraceStore.get("dbg.copilot.commandAdvice");
        assertTrue(rawAdvice instanceof List<?>);
        Map<?, ?> advice = (Map<?, ?>) ((List<?>) rawAdvice).get(0);
        assertEquals("unavailable", advice.get("sourceStatus"));
        assertEquals("trace_id_missing", advice.get("reasonCode"));
        assertFalse(String.valueOf(TraceStore.get("dbg.copilot.actions")).contains("$TRACE_ID_HASH"));
    }

    @Test
    void preservesAlreadyNormalizedTraceHashWhenMdcIsAbsent() {
        String traceHash = SafeRedactor.hashValue("fallback-request-42");
        TraceStore.put("trace.id", traceHash);
        TraceStore.put("dbg.search.enabled", true);

        new DebugCopilotService().maybeEnrichTrace();

        Map<?, ?> advice = (Map<?, ?>) ((List<?>) TraceStore.get("dbg.copilot.commandAdvice")).get(0);
        Map<?, ?> arguments = (Map<?, ?>) advice.get("arguments");
        assertEquals(traceHash, arguments.get("traceIdHash"));
        assertEquals(traceHash, TraceStore.get("dbg.copilot.traceId"));
        assertTrue(String.valueOf(TraceStore.get("dbg.copilot.actions")).contains(traceHash));
    }
}
