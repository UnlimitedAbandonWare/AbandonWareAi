package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionModeReceiptTest {
    @AfterEach void cleanup() { TraceStore.clear(); }

    @Test void safePerAnswerReceiptSurvivesPersistenceAndContainsNoPrivateInput() throws Exception {
        var meta = Map.<String,Object>of("executionMode.requested", "SELF_ASK", "executionMode.effective", "AUTO",
                "executionMode.reason", "safety-gate", "executionMode.queryCount", 1,
                "executionMode.httpAttempts", 2, "executionMode.expanded", false,
                "rawQuery", "private query fixture", "ownerToken", "private owner fixture");
        var pipeline = ChatStreamSignalBuilder.buildPipelineSnapshot(meta, null, null, null);
        assertNotNull(pipeline, "mode-only snapshot must not be dropped");
        var persisted = ChatStreamSignalBuilder.withTraceTurnId(pipeline, "RAG", 42L);
        var json = new ObjectMapper().valueToTree(persisted);
        assertEquals("SELF_ASK", json.path("executionMode").path("requested").asText());
        assertEquals("AUTO", json.path("executionMode").path("effective").asText());
        assertEquals("safety-gate", json.path("executionMode").path("reason").asText());
        assertEquals(2, json.path("executionMode").path("httpAttempts").asInt());
        assertFalse(json.toString().contains("private"));
    }

    @Test void unobservedReceiptIsOmittedAndPoisonedLabelsCannotBecomeModes() throws Exception {
        var pipeline = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of("executionMode.requested", "BYPASS ownerToken=private", "route", "fixture"), null, null, null);
        var json = new ObjectMapper().valueToTree(pipeline);
        assertTrue(json.path("executionMode").isNull() || json.path("executionMode").isMissingNode());
        assertFalse(json.toString().contains("ownerToken"));
    }
}
