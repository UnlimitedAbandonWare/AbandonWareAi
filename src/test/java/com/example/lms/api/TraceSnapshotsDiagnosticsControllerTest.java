package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceSnapshotsDiagnosticsControllerTest {

    @BeforeEach
    void clearTraceBefore() {
        TraceStore.clear();
    }

    @AfterEach
    void clearTraceAfter() {
        TraceStore.clear();
    }

    @Test
    void htmlFallbackDoesNotEchoRawSnapshotIdWhenStoreIsUnavailable() {
        String rawId = "trace-secret-id-12345";
        ObjectProvider<TraceSnapshotStore> provider = mockProvider(null);
        TraceSnapshotsDiagnosticsController controller = new TraceSnapshotsDiagnosticsController(provider);

        ResponseEntity<String> response = controller.getHtml(rawId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        String body = response.getBody();
        assertFalse(body.contains(rawId));
        assertTrue(body.contains("idHash"));
        assertTrue(body.contains(SafeRedactor.hashValue(rawId)));
    }

    @Test
    void htmlFallbackDoesNotEchoRawSnapshotIdWhenSnapshotIsMissing() {
        String rawId = "trace-secret-id-67890";
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        when(store.get(rawId)).thenReturn(Optional.empty());
        ObjectProvider<TraceSnapshotStore> provider = mockProvider(store);
        TraceSnapshotsDiagnosticsController controller = new TraceSnapshotsDiagnosticsController(provider);

        ResponseEntity<String> response = controller.getHtml(rawId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        String body = response.getBody();
        assertFalse(body.contains(rawId));
        assertTrue(body.contains("idHash"));
        assertTrue(body.contains(SafeRedactor.hashValue(rawId)));
    }

    @Test
    void htmlFallbackForSnapshotWithoutStoredHtmlRedactsRawMetadata() {
        String rawId = "trace-raw-id-24680";
        String rawSid = "trace-raw-session-24680";
        String rawTraceId = "trace-raw-trace-24680";
        String rawRequestId = "trace-raw-request-24680";
        String rawPath = "/api/diagnostics/trace/snapshots?token=private-query-value";
        String rawError = "private stack message";
        TraceSnapshotStore.TraceSnapshot snapshot = new TraceSnapshotStore.TraceSnapshot(
                rawId,
                1L,
                "2026-06-05T00:00:00Z",
                rawSid,
                rawSid,
                rawTraceId,
                rawRequestId,
                "unit_test",
                "GET",
                rawPath,
                500,
                rawError,
                false,
                2,
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                false);
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        when(store.get(rawId)).thenReturn(Optional.of(snapshot));
        ObjectProvider<TraceSnapshotStore> provider = mockProvider(store);
        TraceSnapshotsDiagnosticsController controller = new TraceSnapshotsDiagnosticsController(provider);

        ResponseEntity<String> response = controller.getHtml(rawId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertFalse(body.contains(rawId));
        assertFalse(body.contains(rawSid));
        assertFalse(body.contains(rawTraceId));
        assertFalse(body.contains(rawRequestId));
        assertFalse(body.contains(rawPath));
        assertFalse(body.contains(rawError));
        assertTrue(body.contains("idHash"));
        assertTrue(body.contains("sidHash"));
        assertTrue(body.contains("traceIdHash"));
        assertTrue(body.contains("requestIdHash"));
        assertTrue(body.contains("pathHash"));
        assertTrue(body.contains("errorHash"));
        assertTrue(body.contains(SafeRedactor.hashValue(rawId)));
    }

    @Test
    void latestHarmonyHtmlReturnsNewestHarmonySnapshotWithoutClientProvidedRawId() {
        TraceSnapshotStore.TraceSnapshot nonHarmony = new TraceSnapshotStore.TraceSnapshot(
                "trace-non-harmony-id",
                1L,
                "2026-06-05T00:00:00Z",
                "",
                "",
                "",
                "",
                "unit_test",
                "GET",
                "/api/chat/stream",
                200,
                null,
                false,
                1,
                Map.of(),
                Map.of("debug.ai.metrics.nextAction", "continue_observing_chat_harmony"),
                Map.of(),
                "<html><body>non harmony</body></html>",
                false);
        TraceSnapshotStore.TraceSnapshot harmony = new TraceSnapshotStore.TraceSnapshot(
                "trace-harmony-id",
                2L,
                "2026-06-05T00:00:01Z",
                "",
                "",
                "",
                "",
                "chat.trace_html.final",
                "SSE",
                "/api/chat/stream",
                200,
                null,
                true,
                3,
                Map.of(),
                Map.of(
                        "chat.harmony.postprocess.decision", "fallback_evidence",
                        "chat.harmony.postprocess.agentVisible", true,
                        "chat.harmony.postprocess.reason", "missing_external_evidence",
                        "chat.harmony.postprocess.weightedScore", 0.27,
                        "chat.harmony.postprocess.evidenceCount", 0,
                        "chat.harmony.postprocess.answerLength", 42,
                        "debug.ai.metrics.nextAction", "inspect_chat_harmony_trace"),
                Map.of(),
                "<html><body><h3>Trace Memory Checkpoint</h3><dd>unknown</dd></body></html>",
                false);
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        when(store.listSummaries(50)).thenReturn(java.util.List.of(
                Map.of("id", "trace-non-harmony-id"),
                Map.of("id", "trace-harmony-id")));
        when(store.get("trace-non-harmony-id")).thenReturn(Optional.of(nonHarmony));
        when(store.get("trace-harmony-id")).thenReturn(Optional.of(harmony));
        ObjectProvider<TraceSnapshotStore> provider = mockProvider(store);
        TraceSnapshotsDiagnosticsController controller = new TraceSnapshotsDiagnosticsController(provider);

        ResponseEntity<String> response = controller.latestHarmonyHtml();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertTrue(body.contains("chat.harmony.postprocess.decision</th><td>fallback_evidence</td>"));
        assertTrue(body.contains("chat.harmony.postprocess.reason</th><td>missing_external_evidence</td>"));
        assertTrue(body.contains("chat.harmony.postprocess.answerLength</th><td>42</td>"));
        assertTrue(body.contains("inspect_chat_harmony_trace"));
        assertFalse(body.contains("non harmony"));
        assertFalse(body.contains("Trace Memory Checkpoint"));
        assertFalse(body.contains("<dd>unknown</dd>"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void latestTraceMemoryCheckpointPayloadIncludesVirtualCheckpointSummary() {
        TraceSnapshotStore.TraceSnapshot traceMemory = new TraceSnapshotStore.TraceSnapshot(
                "trace-memory-id",
                3L,
                "2026-06-05T00:00:02Z",
                "",
                "",
                "",
                "",
                "trace_memory_self_probe",
                "GET",
                "/api/diagnostics/trace/memory/self-probe",
                200,
                null,
                true,
                8,
                Map.of(),
                Map.of(
                        "traceMemory.fingerprint.current", "hash:abcdef123456",
                        "traceMemory.checkpoint.stage", "load",
                        "traceMemory.checkpoint.phase", "memory.loader.assembled",
                        "traceMemory.virtualCheckpoint.latestKey",
                        "traceMemory.virtualCheckpoint.first_refinement",
                        "traceMemory.virtualCheckpoint.latestStage", "first_refinement",
                        "traceMemory.virtualCheckpoint.latestPhase", "memory.refine.primary"),
                Map.of(),
                null,
                false);
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        when(store.listSummaries(50)).thenReturn(List.of(Map.of("id", "trace-memory-id")));
        when(store.get("trace-memory-id")).thenReturn(Optional.of(traceMemory));
        ObjectProvider<TraceSnapshotStore> provider = mockProvider(store);
        TraceSnapshotsDiagnosticsController controller = new TraceSnapshotsDiagnosticsController(provider);

        ResponseEntity<Map<String, Object>> response = controller.latestTraceMemoryCheckpoints();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        Map<String, Object> virtualCheckpoint = (Map<String, Object>) body.get("virtualCheckpoint");
        assertEquals("traceMemory.virtualCheckpoint.first_refinement", virtualCheckpoint.get("latestKey"));
        assertEquals("first_refinement", virtualCheckpoint.get("latestStage"));
        assertEquals("memory.refine.primary", virtualCheckpoint.get("latestPhase"));
        assertFalse(virtualCheckpoint.toString().contains("trace-memory-id"));
    }

    @Test
    void latestTraceMemoryHtmlRegeneratesRecoverySummaryFromTraceWhenStoredHtmlIsStale() {
        TraceSnapshotStore.TraceSnapshot traceMemory = new TraceSnapshotStore.TraceSnapshot(
                "trace-memory-id",
                3L,
                "2026-06-05T00:00:02Z",
                "",
                "",
                "",
                "",
                "chat.trace_html.final",
                "SSE",
                "/api/chat/stream",
                200,
                null,
                true,
                8,
                Map.of(),
                Map.of(
                        "traceMemory.fingerprint.current", "hash:abcdef123456",
                        "traceMemory.checkpoint.stage", "load",
                        "traceMemory.checkpoint.phase", "memory.loader.assembled",
                        "traceMemory.errorBreak.risk", "none"),
                Map.of(),
                "<html><body><dt>Failure class</dt><dd>unknown</dd></body></html>",
                false);
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        when(store.listSummaries(50)).thenReturn(List.of(Map.of("id", "trace-memory-id")));
        when(store.get("trace-memory-id")).thenReturn(Optional.of(traceMemory));
        TraceSnapshotsDiagnosticsController controller = new TraceSnapshotsDiagnosticsController(mockProvider(store));

        ResponseEntity<String> response = controller.latestTraceMemoryHtml();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertTrue(body.contains("traceMemory.recovery.failureClass</th><td>none</td>"));
        assertTrue(body.contains("traceMemory.recovery.action</th><td>none</td>"));
        assertTrue(body.contains("traceMemory.recovery.route</th><td>none</td>"));
        assertFalse(body.contains("<dd>unknown</dd>"));
    }

    @Test
    void invalidTraceMemoryNumericCheckpointLeavesRedactedBreadcrumb() {
        String rawMetric = "bad-history-size-" + com.example.lms.test.SecretFixtures.openAiKey();
        TraceSnapshotStore.TraceSnapshot traceMemory = new TraceSnapshotStore.TraceSnapshot(
                "trace-memory-id",
                3L,
                "2026-06-05T00:00:02Z",
                "",
                "",
                "",
                "",
                "trace_memory_self_probe",
                "GET",
                "/api/diagnostics/trace/memory/self-probe",
                200,
                null,
                true,
                8,
                Map.of(),
                Map.of(
                        "traceMemory.fingerprint.current", "hash:abcdef123456",
                        "traceMemory.checkpoint.stage", "load",
                        "traceMemory.checkpoint.historySize", rawMetric),
                Map.of(),
                null,
                false);
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        when(store.listSummaries(50)).thenReturn(List.of(Map.of("id", "trace-memory-id")));
        when(store.get("trace-memory-id")).thenReturn(Optional.of(traceMemory));
        TraceSnapshotsDiagnosticsController controller = new TraceSnapshotsDiagnosticsController(mockProvider(store));

        ResponseEntity<Map<String, Object>> response = controller.latestTraceMemoryCheckpoints();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0L, response.getBody().get("checkpointHistorySize"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceMemory.diagnostics.numericCoercion.failed"));
        assertEquals("traceMemory.checkpoint.historySize",
                TraceStore.get("traceMemory.diagnostics.numericCoercion.field"));
        assertEquals("invalid_number", TraceStore.get("traceMemory.diagnostics.numericCoercion.errorType"));
        assertEquals(SafeRedactor.hashValue(rawMetric),
                TraceStore.get("traceMemory.diagnostics.numericCoercion.valueHash"));
        assertEquals(rawMetric.length(),
                TraceStore.get("traceMemory.diagnostics.numericCoercion.valueLength"));
        String traceDump = TraceStore.getAll().toString();
        assertFalse(traceDump.contains(rawMetric));
        assertFalse(traceDump.contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TraceSnapshotStore> mockProvider(TraceSnapshotStore store) {
        ObjectProvider<TraceSnapshotStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }
}
