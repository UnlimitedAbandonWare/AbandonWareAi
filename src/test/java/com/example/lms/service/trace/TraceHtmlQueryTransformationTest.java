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

    @Test void directRetrievalBypassAndExistingDiagnosticFamiliesAreVisibleWithoutReexecution() {
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("queryTransformer.bypassed", "true");
        input.put("queryTransformer.reason", "retrieval_off_direct");
        input.put("queryTransformer.bypassed.queryLength", 21);
        input.put("keywordSelection.mode", "fallback_blank");
        input.put("keywordSelection.maxMust", 3);
        input.put("keywordSelection.fallback.must.count", 2);
        input.put("embed.actualDim", 768);
        input.put("embed.normalizeApplied", false);
        input.put("vector.fp.dropped", 4);
        input.put("vector.fp.blockedReason", "embedding_space_unverified");
        input.put("orch.auxDegraded", true);
        input.put("keywordSelection.unselectedRaw", "synthetic private selection text");
        input.put("vector.unselectedRaw", "synthetic private vector text");
        Map<String, Object> before = Map.copyOf(input);
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);
        String html = builder.buildSplitPanel(null, null, null, null, input);
        for (String group : java.util.List.of("Query Transformation", "Keyword Selection", "Embedding", "Vector", "Mode"))
            assertTrue(html.contains(">" + group + "<"), group);
        for (String key : before.keySet())
            if (!key.endsWith("unselectedRaw")) assertTrue(html.contains(key), key);
        assertTrue(html.contains("retrieval_off_direct"));
        assertFalse(html.contains("synthetic private"));
        assertEquals(html, builder.buildSplitPanel(null, null, null, null, input));
        assertEquals(before, input);
    }

    @Test void legacyQueryFlagNormalizationIsExactAndRepeatable() {
        Object flag = com.example.lms.trace.SafeRedactor.diagnosticValue("queryTransformer.bypassed", "true");
        assertEquals(Boolean.TRUE, flag);
        assertEquals(flag, com.example.lms.trace.SafeRedactor.diagnosticValue("queryTransformer.bypassed", flag));
        assertEquals(Boolean.FALSE, com.example.lms.trace.SafeRedactor.diagnosticValue("queryTransformer.bypassed", "false"));
        for (String key : java.util.List.of("queryTransformer.bypassed", "queryTransformer.reason")) {
            Object unsafe = com.example.lms.trace.SafeRedactor.diagnosticValue(key, "synthetic private prompt");
            assertFalse(String.valueOf(unsafe).contains("synthetic private"));
        }
        assertNotEquals(Boolean.TRUE, com.example.lms.trace.SafeRedactor.diagnosticValue("queryTransformer.other", "true"));
    }
}
