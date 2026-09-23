package com.example.lms.web;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.ai.DebugAiMetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatUsageHeartbeatProjectionTest {

    @Test
    void heartbeatAllowlistProjectsChatUsageWithoutPromotingMissingUsageToWarn() {
        Map<String, Object> chatUsage = new LinkedHashMap<>();
        chatUsage.put("schemaVersion", "awx.chat-usage.v1");
        chatUsage.put("captureEnabled", true);
        chatUsage.put("observed", false);
        chatUsage.put("counterScope", "process_lifetime");
        chatUsage.put("wireAttemptCoverage", "not_observed");
        chatUsage.put("modelInvocations", Map.of("attempts", 0L));
        chatUsage.put("answerExpansion", Map.of("invocations", 0L));

        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("scorecard", Map.of(
                "chatUsage", chatUsage,
                "historyComparisonComparable", false,
                "historyComparisonReason", "baseline_missing"));
        compact.put("tiles", List.of());
        compact.put("planUsage", List.of());
        compact.put("totalEvents", 0L);
        compact.put("warnEvents", 0L);
        compact.put("errorEvents", 0L);
        compact.put("virtualMatrixHotChunks", List.of());
        DebugAiMetricsService service = new DebugAiMetricsService((DebugEventStore) null) {
            @Override
            public Map<String, Object> compactSnapshot(int limit, long windowMs) {
                return compact;
            }
        };

        Map<String, Object> heartbeat = ChatUiCoreHeartbeatProbe.snapshot(
                "unit_test",
                provider(null), provider(null), provider(null), provider(null),
                provider(null), provider(null), provider(service), provider(null));
        Map<String, Object> metrics = nested(heartbeat, "debugAiMetrics");
        Map<String, Object> projected = nested(metrics, "chatUsage");

        assertEquals("awx.chat-usage.v1", projected.get("schemaVersion"));
        assertEquals(Boolean.FALSE, projected.get("observed"));
        assertEquals("process_lifetime", projected.get("counterScope"));
        assertFalse("WARN".equals(metrics.get("status")), "usage:unknown must not create WARN");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.get(key);
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
