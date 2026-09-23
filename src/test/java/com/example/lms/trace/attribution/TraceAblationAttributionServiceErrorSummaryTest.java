package com.example.lms.trace.attribution;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceAblationAttributionServiceErrorSummaryTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void analyzeFailureEmitsRedactedErrorSummaryToResultAndTraceStore() {
        Map<String, Object> trace = new AbstractMap<>() {
            @Override
            public Object get(Object key) {
                throw new RuntimeException("raw query text ownerToken=secret Authorization=Bearer " + "local-placeholder");
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public Set<Entry<String, Object>> entrySet() {
                return Set.of(Map.entry("trigger", "failure"));
            }
        };

        TraceAblationAttributionResult result = assertDoesNotThrow(
                () -> new TraceAblationAttributionService().analyze(trace, null, null));

        assertEquals("ERROR", result.outcome());
        assertTrue(result.debug().get("errorSummary") instanceof Map<?, ?>);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.debug().get("errorSummary");
        assertEquals("analysis", summary.get("stage"));
        assertEquals("analysis_failed", summary.get("reason"));
        assertEquals("RuntimeException", summary.get("errorType"));
        assertFalse(String.valueOf(summary.get("failureClass")).isBlank());

        assertEquals(summary.get("failureClass"), TraceStore.get("taa.error.failureClass"));
        assertEquals("RuntimeException", TraceStore.get("taa.error.type"));
        assertEquals("analysis_failed", TraceStore.get("taa.error.reason"));
        assertTrue(TraceStore.get("taa.error.summary") instanceof Map<?, ?>);

        String publicPayload = result.debug() + " " + TraceStore.getAll();
        assertFalse(publicPayload.contains("raw query text"), publicPayload);
        assertFalse(publicPayload.contains("ownerToken"), publicPayload);
        assertFalse(publicPayload.contains("Authorization"), publicPayload);
        assertFalse(publicPayload.contains("local-placeholder"), publicPayload);
    }

    @Test
    void traceAttributionErrorSanitizesCallerSuppliedSummaryAtWriteBoundary() throws Exception {
        Method method = TraceAblationAttributionService.class.getDeclaredMethod(
                "traceAttributionError", Map.class);
        method.setAccessible(true);
        String rawReason = "private taa reason ownerToken=secret Authorization=Bearer " + "local-placeholder";
        String rawFailure = "private failure api_key=<test-api-key>";
        Map<String, Object> debug = Map.of("errorSummary", Map.of(
                "stage", "analysis",
                "reason", rawReason,
                "failureClass", rawFailure,
                "errorType", "RuntimeException",
                "recoverable", true));

        method.invoke(null, debug);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains(rawFailure), trace);
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("Authorization"), trace);
        assertTrue(String.valueOf(TraceStore.get("taa.error.reason")).contains("hash:"), trace);
        assertTrue(String.valueOf(TraceStore.get("taa.error.failureClass")).contains("hash:"), trace);
    }
}
