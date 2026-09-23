package ai.abandonware.nova.orch.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchTraceRagEventTest {

    @AfterEach
    void clearTrace() {
        MDC.clear();
        TraceStore.clear();
    }

    @Test
    void breadcrumbAndDebugSinkFailureLeavesBreadcrumbAndRedactedTraceReason() {
        TraceStore.put("dbgSearch", true);
        DebugEventStore throwingStore = new DebugEventStore() {
            @Override
            public void emit(DebugProbeType probe,
                    DebugEventLevel level,
                    String fingerprint,
                    String message,
                    String where,
                    Map<String, Object> data,
                    Throwable error) {
                throw new IllegalStateException("debug sink failed ownerToken=secret-orch-event");
            }
        };

        assertDoesNotThrow(() -> OrchEventEmitter.breadcrumbAndDebug(
                throwingStore,
                DebugProbeType.ORCHESTRATION,
                DebugEventLevel.WARN,
                "orch.test",
                "safe orchestration message",
                "OrchEventEmitterTest",
                "orch.test.kind",
                "unit",
                "debug-sink-failure",
                Map.of("queryHash", "abc123", "queryLength", 12),
                null));

        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("orch.test.kind"), trace);
        assertTrue(trace.contains("orch.debugEvent.emit.failed"), trace);
        assertTrue(trace.contains("orch_debug_event_emit_failed"), trace);
        assertFalse(trace.contains("IllegalStateException"), trace);
        assertFalse(trace.contains("secret-orch-event"), trace);
    }

    @Test
    void orchEventEmitterDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/trace/OrchEventEmitter.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "orchestration event emitter fail-soft paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertFalse(source.contains("catch (Throwable ignore)"),
                "TraceStore debug-enable failure must leave a fixed-stage breadcrumb");
        assertTrue(source.contains("traceSuppressed(\"isDebugEnabled\", e);"));
        assertTrue(source.contains("MDC.put(\"orch.debugEvent.suppressed.stage\", stage);"));
        assertFalse(source.contains("failure.getMessage()"));
    }

    @Test
    void ragEventUsesFixedContractAndActiveGatedControl() {
        MDC.put("sid", "raw-session-id");
        MDC.put("traceId", "raw-trace-id");
        MDC.put("x-request-id", "raw-request-id");

        String rawQuery = "never write this raw query";
        OrchEventEmitter.ragEvent(
                "rag.pipeline",
                "retrieval",
                "search",
                "complete",
                "UnitTest",
                "empty",
                Map.of(
                        "query", rawQuery,
                        "queryHash", "abc123",
                        "queryLen", rawQuery.length(),
                        "requestedTopK", 5,
                        "planId", "unit",
                        "mode", "active_gated"),
                Map.of(
                        "returnedCount", 0,
                        "afterFilterCount", 0,
                        "selectedCount", 0,
                        "stageMs", 7,
                        "sourceDiversity", 0.0d),
                Map.of(
                        "reasonCode", "zero_result",
                        "failureClass", "zero_result",
                        "exceptionType", "None"),
                Map.of());

        Object eventsObj = TraceStore.get("orch.events.v1");
        assertTrue(eventsObj instanceof List<?>);
        Map<?, ?> event = (Map<?, ?>) ((List<?>) eventsObj).get(0);

        assertEquals(1, event.get("v"));
        for (String key : List.of("seq", "ts", "traceId", "sessionId", "requestId", "kind", "phase",
                "stage", "step", "component", "status", "input", "output", "failure", "control")) {
            assertTrue(event.containsKey(key), "missing key " + key);
        }
        assertEquals(SafeRedactor.hashValue("raw-trace-id"), event.get("traceId"));
        assertEquals(SafeRedactor.hashValue("raw-session-id"), event.get("sessionId"));
        assertEquals(SafeRedactor.hashValue("raw-request-id"), event.get("requestId"));
        assertFalse(event.toString().contains("raw-trace-id"));
        assertFalse(event.toString().contains("raw-session-id"));
        assertFalse(event.toString().contains("raw-request-id"));
        assertFalse(event.toString().contains(rawQuery));

        Map<?, ?> input = (Map<?, ?>) event.get("input");
        assertFalse(input.containsKey("query"));
        assertEquals("abc123", input.get("queryHash"));

        Map<?, ?> control = (Map<?, ?>) event.get("control");
        assertEquals("recovery", control.get("action"));
        assertEquals(Boolean.TRUE, control.get("applied"));
        assertEquals(Boolean.TRUE, TraceStore.get("rag.control.recovery.requested"));
    }

    @Test
    void ragEventCanInferControlFromRedactedTraceAnchorPressure() {
        TraceStore.put("ablation.traceAnchor.top", Map.of(
                "anchorHash", "abc123",
                "evidenceDigestHash", "def456",
                "matrixTile", 5,
                "routeHint", "brave_mode",
                "expectedDelta", 0.72d));
        TraceStore.put("ablation.traceAnchor.routeCorrectionNeed", 0.72d);

        OrchEventEmitter.ragEvent(
                "rag.pipeline",
                "retrieval",
                "rerank",
                "complete",
                "UnitTest",
                "ok",
                Map.of("queryHash", "abc123", "queryLen", 12, "requestedTopK", 5, "mode", "active_gated"),
                Map.of("returnedCount", 4, "afterFilterCount", 4, "selectedCount", 3),
                Map.of(),
                Map.of());

        Object eventsObj = TraceStore.get("orch.events.v1");
        assertTrue(eventsObj instanceof List<?>);
        Map<?, ?> event = (Map<?, ?>) ((List<?>) eventsObj).get(0);
        Map<?, ?> control = (Map<?, ?>) event.get("control");

        assertEquals("brave_mode", control.get("action"));
        assertEquals("trace_anchor_brave", control.get("reasonCode"));
        assertEquals("abc123", control.get("anchorHash"));
        assertEquals("brave_mode", control.get("routeHint"));
        assertEquals(Boolean.TRUE, TraceStore.get("rag.control.brave.requested"));
        assertEquals("abc123", TraceStore.get("rag.control.last.anchorHash"));
    }

    @Test
    void ragControlTraceSignalsMaskSensitiveScalars() {
        String fakeSecret = "sk-" + "A".repeat(24);
        String rawBreadcrumb = "rag-recovery-" + fakeSecret;
        String rawStage = "search-" + fakeSecret;

        OrchEventEmitter.ragEvent(
                "rag.pipeline",
                "retrieval",
                rawStage,
                "complete",
                "UnitTest",
                "empty",
                Map.of("queryHash", "abc123", "queryLen", 12, "requestedTopK", 5, "mode", "unit"),
                Map.of("returnedCount", 0, "afterFilterCount", 0, "selectedCount", 0),
                Map.of("reasonCode", "zero_result", "failureClass", "zero_result"),
                Map.of("breadcrumbId", rawBreadcrumb));

        Object storedBreadcrumb = TraceStore.get("rag.control.last.breadcrumbId");
        Object storedStage = TraceStore.get("rag.control.last.stage");

        assertTrue(String.valueOf(storedBreadcrumb).startsWith("hash:"), String.valueOf(storedBreadcrumb));
        assertTrue(String.valueOf(storedStage).startsWith("hash:"), String.valueOf(storedStage));
        assertFalse(String.valueOf(storedBreadcrumb).contains(fakeSecret));
        assertFalse(String.valueOf(storedStage).contains(fakeSecret));
    }

    @Test
    void ragEventLabelsDoNotExposeRawSensitiveValues() {
        String fakeSecret = "sk-" + "A".repeat(24);
        String rawLabel = "ownertoken " + fakeSecret;

        OrchEventEmitter.ragEvent(
                "rag.pipeline " + rawLabel,
                "retrieval",
                "search " + rawLabel,
                "complete",
                "UnitTest " + rawLabel,
                "ok",
                Map.of("queryHash", "abc123", "queryLen", 12, "requestedTopK", 5, "mode", "unit"),
                Map.of("returnedCount", 1, "afterFilterCount", 1, "selectedCount", 1),
                Map.of(),
                Map.of());

        String rendered = String.valueOf(TraceStore.get(OrchTrace.TRACE_KEY_EVENTS_V1));
        assertFalse(rendered.contains(fakeSecret), rendered);
        assertFalse(rendered.toLowerCase().contains("ownertoken"), rendered);
        assertTrue(rendered.contains("hash_"), rendered);
    }

    @Test
    void genericEventStoresCorrelationAsHashOnly() {
        MDC.put("sid", "raw-generic-session");
        MDC.put("traceId", "raw-generic-trace");
        MDC.put("requestId", "raw-generic-request");

        Map<String, Object> event = OrchTrace.newEvent("unit", "phase", "step", Map.of());

        assertEquals(SafeRedactor.hashValue("raw-generic-session"), event.get("sid"));
        assertEquals(SafeRedactor.hashValue("raw-generic-trace"), event.get("traceId"));
        assertEquals(SafeRedactor.hashValue("raw-generic-request"), event.get("requestId"));
        assertFalse(event.toString().contains("raw-generic-session"));
        assertFalse(event.toString().contains("raw-generic-trace"));
        assertFalse(event.toString().contains("raw-generic-request"));
    }

    @Test
    void ragEventNormalizesStatusWithoutLosingRawStatusControlInference() {
        Map<?, ?> blocked = emitStatusEvent("failed_empty_result");

        assertEquals("blocked", blocked.get("status"));
        assertEquals("recovery", ((Map<?, ?>) blocked.get("control")).get("action"));

        Map<?, ?> error = emitStatusEvent("failed");
        assertEquals("error", error.get("status"));

        Map<?, ?> skipped = emitStatusEvent("skipped");
        assertEquals("ok", skipped.get("status"));
    }

    private static Map<?, ?> emitStatusEvent(String status) {
        TraceStore.clear();
        OrchEventEmitter.ragEvent(
                "rag.pipeline",
                "retrieval",
                "search",
                "complete",
                "UnitTest",
                status,
                Map.of("queryHash", "abc123", "queryLen", 12, "requestedTopK", 5, "mode", "unit"),
                Map.of("returnedCount", 0, "afterFilterCount", 0, "selectedCount", 0),
                Map.of(),
                Map.of());

        Object eventsObj = TraceStore.get("orch.events.v1");
        assertTrue(eventsObj instanceof List<?>);
        return (Map<?, ?>) ((List<?>) eventsObj).get(0);
    }
}
