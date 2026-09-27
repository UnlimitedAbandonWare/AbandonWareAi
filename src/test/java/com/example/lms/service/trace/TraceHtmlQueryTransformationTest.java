package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TraceHtmlQueryTransformationTest {
    @AfterEach void clear() { TraceStore.clear(); }

    @Test void producedQueryDecisionsAreVisibleWithoutProducerServices() {
        var input = Map.<String, Object>of(
                "qtx.stagePolicy.enabled", true, "qtx.stagePolicy.clamped", false,
                "qtx.minLiveBudgetMs", 450L, "qtx.constraints.rejectedCount", 2,
                "qtx.constraints.reason", "explicit_constraint_loss");
        // Only the existing renderer is constructed: no model/search/memory service exists.
        var builder = new TraceHtmlBuilder(null);
        String first = builder.buildSplitPanel(null, null, null, null, input);
        assertTrue(first.contains("Query Transformation"));
        input.forEach((key, value) -> assertTrue(first.contains(key), key));
        assertTrue(first.contains("450"));
        assertTrue(first.contains("explicit_constraint_loss"));
        assertEquals(first, builder.buildSplitPanel(null, null, null, null, input));
        assertEquals(5, input.size());
    }

    @Test void unexpectedSensitiveTypesRemainSanitizedAndUnselectedFieldsStayOut() {
        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        String html = new TraceHtmlBuilder(null).buildSplitPanel(null, null, null, null, Map.of(
                "qtx.stagePolicy.enabled", Map.of("prompt", "private synthetic prompt"),
                "qtx.bypass.reason", secret,
                "qtx.unselectedRaw", "private synthetic query"));
        assertTrue(html.contains("Query Transformation"));
        assertFalse(html.contains(secret));
        assertFalse(html.contains("private synthetic prompt"));
        assertFalse(html.contains("private synthetic query"));
        assertFalse(html.contains("qtx.unselectedRaw"));
    }

    @Test void unrelatedDiagnosticsDoNotCreateAnEmptyQueryGroup() {
        String html = new TraceHtmlBuilder(null).buildSplitPanel(null, null, null, null,
                Map.of("orch.bypass", false));
        assertFalse(html.contains("Query Transformation"));
    }
}
