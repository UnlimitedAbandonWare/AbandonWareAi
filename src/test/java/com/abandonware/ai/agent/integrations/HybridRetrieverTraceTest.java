package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRetrieverTraceTest {

    private static final String RAW_SCORE = "raw private hybrid score token";

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void invalidScoreFallbackRecordsRedactedBreadcrumb() throws Exception {
        Method toDouble = HybridRetriever.class.getDeclaredMethod("toDouble", Object.class);
        toDouble.setAccessible(true);

        double result = (double) toDouble.invoke(null, RAW_SCORE);

        assertEquals(0.0d, result, 1.0e-9d);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.hybridRetriever.suppressed"));
        assertEquals("score.parseFallback", TraceStore.get("agent.hybridRetriever.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("agent.hybridRetriever.suppressed.errorType"));
        assertEquals(RAW_SCORE.length(), TraceStore.get("agent.hybridRetriever.suppressed.valueLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(RAW_SCORE));
    }

    @Test
    void failSoftCatchBlocksKeepTraceSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/abandonware/ai/agent/integrations/HybridRetriever.java"));

        List<String> anchors = List.of(
                "index.ensureBuilt();",
                "fused = fx.rerank(query, fused, k, domain, null);",
                "CrossEncoder ce = new OnnxCrossEncoder();",
                "Double.parseDouble(String.valueOf(o))");

        for (String anchor : anchors) {
            int index = source.indexOf(anchor);
            assertTrue(index >= 0, "missing source anchor: " + anchor);
            String window = source.substring(index, Math.min(source.length(), index + 280));
            assertTrue(window.contains("traceSuppressed("),
                    "catch/fallback near " + anchor + " must emit redacted breadcrumb");
        }
    }
}
