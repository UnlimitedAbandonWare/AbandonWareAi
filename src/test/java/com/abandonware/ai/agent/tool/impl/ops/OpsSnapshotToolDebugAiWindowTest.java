package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.abandonware.ai.agent.contract.ToolManifestSnapshot;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsSnapshotToolDebugAiWindowTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void opsSnapshotPassesRequestedDebugAiWindowMsToCompactSnapshot() throws Exception {
        CapturingMetricsService metrics = new CapturingMetricsService();
        OpsSnapshotTool tool = new OpsSnapshotTool(
                new ToolRegistry(),
                new EmptyCatalog(),
                null,
                provider(new DebugEventStore()),
                provider(metrics));

        ToolResponse response = tool.execute(new ToolRequest(Map.of("debugAiWindowMs", 300_000, "debugAiLimit", 12), null));

        Map<?, ?> snapshot = (Map<?, ?>) response.data().get("snapshot");
        Map<?, ?> debugAi = (Map<?, ?>) snapshot.get("debugAi");
        assertEquals(12, metrics.lastLimit);
        assertEquals(300_000L, metrics.lastWindowMs);
        assertEquals(300_000L, debugAi.get("windowMs"));
    }

    @Test
    void opsSnapshotInvalidDebugAiNumbersLeaveRedactedBreadcrumb() {
        CapturingMetricsService metrics = new CapturingMetricsService();
        OpsSnapshotTool tool = new OpsSnapshotTool(
                new ToolRegistry(),
                new EmptyCatalog(),
                null,
                provider(new DebugEventStore()),
                provider(metrics));

        ToolResponse response = tool.execute(new ToolRequest(
                Map.of("debugAiWindowMs", "private window", "debugAiLimit", "private limit"),
                null));

        Map<?, ?> snapshot = (Map<?, ?>) response.data().get("snapshot");
        Map<?, ?> debugAi = (Map<?, ?>) snapshot.get("debugAi");
        assertEquals(30, metrics.lastLimit);
        assertEquals(60_000L, metrics.lastWindowMs);
        assertEquals(60_000L, debugAi.get("windowMs"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.ops.snapshot.suppressed"));
        assertEquals("debugAiWindowMs", TraceStore.get("agent.ops.snapshot.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("agent.ops.snapshot.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private window"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private limit"));
    }

    @Test
    void opsSnapshotDoesNotReturnRawManifestResourcePath() {
        OpsSnapshotTool tool = new OpsSnapshotTool(
                new ToolRegistry(),
                new EmptyCatalog(),
                null,
                provider(new DebugEventStore()),
                provider(null));

        ToolResponse response = tool.execute(new ToolRequest(Map.of(), null));

        Map<?, ?> snapshot = (Map<?, ?>) response.data().get("snapshot");
        assertFalse(snapshot.containsKey("manifestResource"));
        assertTrue(String.valueOf(snapshot.get("manifestResourceHash")).length() >= 12);
        assertEquals("test".length(), snapshot.get("manifestResourceLength"));
    }

    @Test
    void debugTraceLookupInvalidLimitLeavesRedactedBreadcrumb() {
        DebugTraceLookupTool tool = new DebugTraceLookupTool(
                provider(new DebugEventStore()),
                provider(null));

        ToolResponse response = tool.execute(new ToolRequest(Map.of("limit", "private limit"), null));

        assertEquals(20, response.data().get("limit"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.ops.debugTraceLookup.suppressed"));
        assertEquals("limit", TraceStore.get("agent.ops.debugTraceLookup.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("agent.ops.debugTraceLookup.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private limit"));
    }

    @Test
    void debugTraceLookupParsesTrimmedLimitString() {
        DebugTraceLookupTool tool = new DebugTraceLookupTool(
                provider(new DebugEventStore()),
                provider(null));

        ToolResponse response = tool.execute(new ToolRequest(Map.of("limit", " 7 "), null));

        assertEquals(7, response.data().get("limit"));
        assertEquals(null, TraceStore.get("agent.ops.debugTraceLookup.suppressed"));
    }

    private static final class CapturingMetricsService extends DebugAiMetricsService {
        private int lastLimit;
        private long lastWindowMs;

        private CapturingMetricsService() {
            super(new DebugEventStore());
        }

        @Override
        public Map<String, Object> compactSnapshot(int limit, long windowMs) {
            this.lastLimit = limit;
            this.lastWindowMs = windowMs;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("windowMs", windowMs);
            return out;
        }
    }

    private static final class EmptyCatalog extends ToolManifestCatalog {
        @Override
        public ToolManifestSnapshot load() {
            return new ToolManifestSnapshot("test", Map.of(), java.util.List.of(), java.util.List.of());
        }
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
        };
    }
}
