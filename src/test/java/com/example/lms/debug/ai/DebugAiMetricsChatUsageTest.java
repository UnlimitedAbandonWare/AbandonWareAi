package com.example.lms.debug.ai;

import com.example.lms.debug.DebugEventStore;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class DebugAiMetricsChatUsageTest {

    @Test
    void scorecardProjectsTheSameCountOnlyProcessLedgerSnapshot() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt model = ledger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.explicit(
                        240,
                        2_048,
                        2_048,
                        ChatUsageLedger.ParameterKind.MAX_TOKENS,
                        ChatUsageLedger.CapSource.NORMALIZED_REQUEST));
        model.responseReceived(new TokenUsage(10, 6, 16));

        DebugAiMetricsService service = new DebugAiMetricsService(new DebugEventStore());
        ReflectionTestUtils.setField(service, "chatUsageLedger", ledger);

        Map<String, Object> scorecard = nested(service.compactSnapshot(10), "scorecard");
        Map<String, Object> usage = nested(scorecard, "chatUsage");

        assertEquals("awx.chat-usage.v1", usage.get("schemaVersion"));
        assertEquals("process_lifetime", usage.get("counterScope"));
        assertEquals("not_observed", usage.get("wireAttemptCoverage"));
        assertEquals(1L, number(nested(usage, "modelInvocations"), "attempts"));
        assertEquals(6L, number(nested(usage, "modelInvocations"), "providerOutputTokens"));
        assertFalse(usage.toString().contains("prompt"));
        assertFalse(usage.toString().contains("answer payload"));
        assertFalse(usage.toString().contains("session"));
        assertFalse(usage.toString().contains("model id"));
    }

    @Test
    void compactAndSnapshotExposeTheSameChatUsageObjectForOneBuild() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        DebugAiMetricsService service = new DebugAiMetricsService(new DebugEventStore());
        ReflectionTestUtils.setField(service, "chatUsageLedger", ledger);

        Map<String, Object> compact = service.compactSnapshot(10);
        Map<String, Object> scorecard = nested(compact, "scorecard");

        assertSame(scorecard.get("chatUsage"), scorecard.get("chatUsage"));
        assertEquals(Boolean.FALSE, nested(scorecard, "chatUsage").get("observed"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.get(key);
    }

    private static long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }
}
