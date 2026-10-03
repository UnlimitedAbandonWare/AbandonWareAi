package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DebugTraceLookupCorrelationTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
        TraceStore.clear();
    }

    @Test
    void correlatedLookupFiltersBeforeLimitAndNeverIncludesAnotherRequest() {
        DebugEventStore store = store();
        emit(store, "request-a", "trace-a", "a-first");
        emit(store, "request-a", "trace-a", "a-second");
        emit(store, "request-b", "trace-b", "b-latest");

        DebugTraceLookupTool tool = tool(store);
        String requestHash = SafeRedactor.hashValue("request-a");
        Map<String, Object> first = tool.execute(new ToolRequest(Map.of(
                "mode", "correlated", "requestIdHash", requestHash, "limit", 1), null)).data();
        List<Map<String, Object>> events = events(first);

        assertEquals("correlated", first.get("scope"));
        assertEquals(requestHash, first.get("requestIdHash"));
        assertEquals(1, first.get("returnedCount"));
        assertEquals(1, events.size());
        assertEquals(requestHash, events.get(0).get("requestIdHash"));
        assertFalse(events.toString().contains(SafeRedactor.hashValue("request-b")));
        assertEquals(true, first.get("hasMore"));
        assertNotNull(first.get("nextCursor"));

        Map<String, Object> second = tool.execute(new ToolRequest(Map.of(
                "mode", "correlated", "requestIdHash", requestHash, "limit", 1,
                "cursor", first.get("nextCursor")), null)).data();
        assertEquals(1, events(second).size());
        assertEquals(requestHash, events(second).get(0).get("requestIdHash"));
        assertEquals(false, second.get("hasMore"));
    }

    @Test
    void missingTargetAndInvalidFilterNeverFallBackToGlobalEvents() {
        DebugEventStore store = store();
        emit(store, "request-b", "trace-b", "b-only");
        DebugTraceLookupTool tool = tool(store);

        Map<String, Object> absent = tool.execute(new ToolRequest(Map.of(
                "mode", "correlated", "requestIdHash", SafeRedactor.hashValue("request-a")), null)).data();
        assertTrue(events(absent).isEmpty());
        assertEquals(0, absent.get("returnedCount"));

        ToolInvocationException invalid = assertThrows(ToolInvocationException.class,
                () -> tool.execute(new ToolRequest(Map.of(
                        "mode", "correlated", "requestIdHash", "request-a"), null)));
        assertEquals("invalid_filter", invalid.code());
    }

    @Test
    void targetCannotBeIgnoredByAnotherModeAndGlobalLookupMustBeExplicit() {
        DebugEventStore store = store();
        emit(store, "request-a", "trace-a", "a-only");
        emit(store, "request-b", "trace-b", "b-latest");
        DebugTraceLookupTool tool = tool(store);
        String requestHash = SafeRedactor.hashValue("request-a");
        String eventB = store.list(1).get(0).id();

        assertEquals("ambiguous_target", assertThrows(ToolInvocationException.class,
                () -> tool.execute(new ToolRequest(Map.of(
                        "mode", "global_recent", "requestIdHash", requestHash), null))).code());
        assertEquals("ambiguous_target", assertThrows(ToolInvocationException.class,
                () -> tool.execute(new ToolRequest(Map.of(
                        "mode", "event_id", "eventId", eventB, "requestIdHash", requestHash), null))).code());
        assertEquals("missing_target", assertThrows(ToolInvocationException.class,
                () -> tool.execute(new ToolRequest(Map.of(), null))).code());

        Map<String, Object> global = tool.execute(new ToolRequest(Map.of("mode", "global_recent"), null)).data();
        assertEquals("global_recent", global.get("scope"));
        assertEquals(2, events(global).size());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("events");
    }

    @SuppressWarnings("unchecked")
    private static DebugTraceLookupTool tool(DebugEventStore store) {
        ObjectProvider<DebugEventStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return new DebugTraceLookupTool(provider, null);
    }

    private static DebugEventStore store() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        return store;
    }

    private static void emit(DebugEventStore store, String request, String trace, String fingerprint) {
        MDC.put("x-request-id", request);
        MDC.put("traceId", trace);
        store.emit(DebugProbeType.GENERIC, DebugEventLevel.INFO,
                fingerprint, "synthetic diagnostic", Map.of(), null);
    }
}
