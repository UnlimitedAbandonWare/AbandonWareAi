package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColbertRerankerTraceTest {

    private static final String RAW_SCORE = "private-score-token";

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void colbertLiteInvalidScoreRecordsRedactedParseFallback() {
        ColbertLiteReranker reranker = new ColbertLiteReranker(text -> new float[] {1.0f, 0.0f});

        List<Map<String, Object>> out = reranker.rerank("alpha", itemsWithInvalidScore());

        assertEquals(1, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("agent.colbertLite.score.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("agent.colbertLite.score.parseFallback.errorType"));
        assertEquals(RAW_SCORE.length(), TraceStore.get("agent.colbertLite.score.parseFallback.valueLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(RAW_SCORE));
    }

    @Test
    void colbertInvalidScoreRecordsRedactedParseFallback() {
        ColbertReranker reranker = new ColbertReranker(text -> new float[][] {{1.0f, 0.0f}});

        List<Map<String, Object>> out = reranker.rerank("alpha", itemsWithInvalidScore());

        assertEquals(1, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("agent.colbert.score.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("agent.colbert.score.parseFallback.errorType"));
        assertEquals(RAW_SCORE.length(), TraceStore.get("agent.colbert.score.parseFallback.valueLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(RAW_SCORE));
    }

    @Test
    void colbertLiteNonFiniteScoreFallsBackToFiniteValue() {
        ColbertLiteReranker reranker = new ColbertLiteReranker(text -> new float[] {1.0f, 0.0f});

        List<Map<String, Object>> out = reranker.rerank("alpha", itemsWithScore(Double.NaN));

        assertTrue(Double.isFinite((Double) out.get(0).get("score")));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.colbertLite.score.parseFallback"));
    }

    @Test
    void colbertNonFiniteScoreFallsBackToFiniteValue() {
        ColbertReranker reranker = new ColbertReranker(text -> new float[][] {{1.0f, 0.0f}});

        List<Map<String, Object>> out = reranker.rerank("alpha", itemsWithScore(Double.POSITIVE_INFINITY));

        assertTrue(Double.isFinite((Double) out.get(0).get("score")));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.colbert.score.parseFallback"));
    }

    private static List<Map<String, Object>> itemsWithInvalidScore() {
        return itemsWithScore(RAW_SCORE);
    }

    private static List<Map<String, Object>> itemsWithScore(Object score) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", "alpha");
        item.put("snippet", "beta");
        item.put("score", score);
        return List.of(item);
    }
}
