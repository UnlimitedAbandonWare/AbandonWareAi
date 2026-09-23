package com.example.lms.infra.resilience;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultMaskingLayerMonitorRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void recordSummarizesRawContextAndRedactsThrowableAndNote() {
        FaultMaskingLayerMonitor monitor = new FaultMaskingLayerMonitor();
        String fakeKey = "token=test-secret-abcdefghijklmnop";
        String rawContext = "PROMPT_CONTEXT student private answer about account transfer";

        monitor.record(
                "query-transformer:runLLM",
                new RuntimeException("llm failed " + fakeKey),
                rawContext + " " + fakeKey,
                "caught-and-fallback " + fakeKey);

        String trace = TraceStore.getAll().toString();
        assertFalse(trace.contains(fakeKey));
        assertFalse(trace.contains(rawContext));
        assertTrue(String.valueOf(TraceStore.get("faultmask.context")).contains("hash12"));
    }

    @Test
    void recordStoresStageAsSafeLabel() {
        FaultMaskingLayerMonitor monitor = new FaultMaskingLayerMonitor();
        String rawStage = "Private Stage ownerToken=raw-secret";

        monitor.record(rawStage, new RuntimeException("boom"), "note");

        String trace = TraceStore.getAll().toString();
        assertFalse(trace.contains(rawStage), trace);
        assertFalse(trace.contains("raw-secret"), trace);
        assertTrue(String.valueOf(TraceStore.get("faultmask.stage")).startsWith("hash:"));
    }

    @Test
    void irregularityProfilerReasonsAreTraceSafe() {
        IrregularityProfiler profiler = new IrregularityProfiler();
        GuardContext context = new GuardContext();
        String fakeKey = "token=test-secret-abcdefghijklmnop";
        String rawReason = "irregularity raw fallback Authorization: Bearer " + fakeKey;

        profiler.bump(context, 0.25d, rawReason);

        String trace = TraceStore.getAll().toString();
        assertFalse(trace.contains(fakeKey));
        assertFalse(trace.contains(rawReason));
        assertTrue(trace.contains("irregularity.last"));
        assertTrue(String.valueOf(TraceStore.get("irregularity.last")).startsWith("hash:"));
    }

    @Test
    void irregularityProfilerTraceFailureLeavesFixedStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/infra/resilience/IrregularityProfiler.java"));

        assertTrue(source.contains("traceSuppressed(\"irregularity.trace\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"irregularity.suppressed.\" + safeStage, true);"));
    }

    @Test
    void debugEventSinkFailureLeavesRedactedTraceBreadcrumb() throws Exception {
        FaultMaskingLayerMonitor monitor = new FaultMaskingLayerMonitor();
        DebugEventStore throwingStore = new DebugEventStore() {
            @Override
            public void emit(DebugProbeType probe,
                    DebugEventLevel level,
                    String fingerprint,
                    String message,
                    String where,
                    Map<String, Object> data,
                    Throwable error) {
                throw new IllegalStateException("debug sink failed ownerToken=secret-faultmask-event");
            }
        };
        setPrivateField(monitor, "debugEvents", throwingStore);

        assertDoesNotThrow(() -> monitor.record(
                "faultmask:test",
                new RuntimeException("masked failure"),
                "safe context",
                "safe note"));

        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("faultmask.debugEvent.emit.failed"), trace);
        assertTrue(trace.contains("faultmask_debug_event_emit_failed"), trace);
        assertFalse(trace.contains("IllegalStateException"), trace);
        assertFalse(trace.contains("secret-faultmask-event"), trace);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
