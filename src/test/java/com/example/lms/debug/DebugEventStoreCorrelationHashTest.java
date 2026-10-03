package com.example.lms.debug;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugEventStoreCorrelationHashTest {
    @AfterEach
    void clearContext() {
        TraceStore.clear();
        MDC.clear();
    }

    @Test
    void normalizedFallbackHashRemainsJoinableByExactRequestAndTrace() {
        String traceHash = SafeRedactor.hashValue("fallback-request-42");
        TraceStore.put("trace.id", traceHash);
        MDC.clear();
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);

        store.emit(DebugProbeType.GENERIC, DebugEventLevel.INFO, "correlation-fixture",
                "synthetic event", Map.of("provider", "test"), null);

        assertEquals(traceHash, store.list(1).get(0).traceId());
        assertEquals(traceHash, store.list(1).get(0).requestId());
        assertEquals(1, store.page(traceHash, traceHash, null, 5).items().size());
    }
}
