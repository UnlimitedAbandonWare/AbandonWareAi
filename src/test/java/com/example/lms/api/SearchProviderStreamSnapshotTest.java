package com.example.lms.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SearchProviderStreamSnapshotTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void aggregateSuccessKeepsEachProviderReceiptWithoutPrivateFields() {
        var meta = Map.<String, Object>of(
                "agent.webSearch.prompt.status", "OK",
                "web.naver.filter.runs", List.of(Map.of("provider", "naver", "httpStatus", 401,
                        "clientAttemptObserved", true, "providerReceiptObserved", true,
                        "failureClass", "AUTH_OR_CONFIG", "authorization", "private-fixture")),
                "web.brave.attempt.runs", List.of(Map.of("provider", "brave", "httpStatus", 200,
                        "clientAttemptObserved", true, "providerReceiptObserved", true,
                        "outcome", "OK", "failureReason", "NONE", "returnedCount", 2)));
        JsonNode node = snapshot(meta);
        var rows = node.path("agentWebSearch").path("providers");
        assertEquals(2, rows.size());
        assertEquals("naver", rows.get(0).path("provider").asText());
        assertEquals(401, rows.get(0).path("httpStatus").asInt());
        assertEquals("AUTH_OR_CONFIG", rows.get(0).path("failureReason").asText());
        assertEquals("OK", rows.get(1).path("outcome").asText());
        assertEquals(2, rows.get(1).path("returnedCount").asInt());
        assertFalse(node.toString().contains("private-fixture"));
        assertFalse(node.toString().contains("authorization"));
        assertTrue(rows.get(0).path("recoveredAt").isNull());
    }

    @Test void receiptDoesNotRequireAnAggregateSearchStatus() {
        var node = snapshot(Map.of("web.naver.filter.runs", List.of(Map.of(
                "clientAttemptObserved", true, "providerReceiptObserved", true,
                "httpStatus", 200, "failureClass", "TRUE_ZERO", "afterFilterCount", 0))));
        assertEquals("TRUE_ZERO", node.path("agentWebSearch").path("providers").get(0).path("outcome").asText());
    }

    @Test void aDifferentRequestCannotSupplyThisStreamReceipt() {
        var node = snapshot(Map.of("requestId", "current-request", "agent.webSearch.prompt.status", "OK",
                "web.naver.filter.runs", List.of(Map.of("requestId", "different-request",
                        "clientAttemptObserved", true, "httpStatus", 401, "failureClass", "AUTH_OR_CONFIG"))));
        assertEquals(0, node.path("agentWebSearch").path("providers").size());
    }

    @Test void cachedParsingCannotClaimProviderSuccessOrRecovery() {
        var node = snapshot(Map.of("web.naver.filter.runs", List.of(Map.of(
                "clientAttemptObserved", false, "providerReceiptObserved", false,
                "failureClass", "NONE", "cacheHit", true, "rawSize", 2,
                "recoveredAt", "2026-10-08T00:00:00Z"))));
        var row = node.path("agentWebSearch").path("providers").get(0);
        assertEquals("CACHE_HIT", row.path("outcome").asText());
        assertTrue(row.path("httpStatus").isNull());
        assertTrue(row.path("recoveredAt").isNull());
    }

    @Test void activeSearchExecutionRejectsForeignOrUncorrelatedReceipts() {
        for (var row : List.of(Map.of("searchExecutionId", "hash:222222222222", "failureClass", "NONE"),
                Map.of("failureClass", "NONE"))) {
            var node = snapshot(Map.of("searchExecutionId", "hash:111111111111",
                    "agent.webSearch.prompt.status", "OK", "web.naver.filter.runs", List.of(row)));
            assertEquals(0, node.path("agentWebSearch").path("providers").size());
        }
    }

    @Test void cacheHitOverridesRetainedOriginalAttemptAndRecoveryFlags() {
        var node = snapshot(Map.of("web.naver.filter.runs", List.of(Map.of(
                "clientAttemptObserved", true, "providerReceiptObserved", true,
                "httpStatus", 200, "failureClass", "NONE", "cacheHit", true,
                "recoveredAt", "2026-10-08T00:00:00Z", "finishedAtEpochMs", System.currentTimeMillis()))));
        var row = node.path("agentWebSearch").path("providers").get(0);
        assertEquals("CACHE_HIT", row.path("outcome").asText());
        assertTrue(row.path("recoveredAt").isNull());
    }

    private JsonNode snapshot(Map<String, Object> meta) {
        return json.valueToTree(ChatStreamSignalBuilder.buildPipelineSnapshot(meta, null, null, null));
    }

    @Test void fallbackMaskIsProjectedOnlyForItsMatchingAttemptAndExecution() {
        var failed = Map.of("providerAttemptId", "hash:111111111111", "searchExecutionId", "hash:222222222222",
                "clientAttemptObserved", true, "providerReceiptObserved", true, "httpStatus", 401, "failureClass", "AUTH_OR_CONFIG");
        for (String maskAttempt : List.of("hash:111111111111", "hash:333333333333")) {
            var node = snapshot(Map.of("searchExecutionId", "hash:222222222222",
                    "web.naver.filter.runs", List.of(failed), "web.naver.masked.runs", List.of(Map.of(
                            "providerAttemptId", maskAttempt, "searchExecutionId", "hash:222222222222", "maskedBy", "brave"))));
            var row = node.path("agentWebSearch").path("providers").get(0);
            if (maskAttempt.equals("hash:111111111111")) assertEquals("brave", row.path("maskedBy").asText());
            else assertTrue(row.path("maskedBy").isNull());
            assertTrue(row.path("recoveredAt").isNull());
        }
    }
}
