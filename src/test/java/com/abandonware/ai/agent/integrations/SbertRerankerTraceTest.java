package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SbertRerankerTraceTest {

    private static final String RAW_SCORE = "raw private sbert score token";

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void sbertInvalidScoreRecordsRedactedParseFallback() {
        SbertReranker reranker = new SbertReranker(text -> new float[] {1.0f, 0.0f});

        List<Map<String, Object>> out = reranker.rerank("alpha", itemsWithInvalidScore());

        assertEquals(1, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("agent.sbert.score.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("agent.sbert.score.parseFallback.errorType"));
        assertEquals(RAW_SCORE.length(), TraceStore.get("agent.sbert.score.parseFallback.valueLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(RAW_SCORE));
    }

    @Test
    void sbertNonFiniteScoreFallsBackToFiniteValue() {
        SbertReranker reranker = new SbertReranker(text -> new float[] {1.0f, 0.0f});

        List<Map<String, Object>> out = reranker.rerank("alpha", itemsWithScore(Double.NaN));

        assertTrue(Double.isFinite((Double) out.get(0).get("score")));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.sbert.score.parseFallback"));
    }

    @Test
    void sbertPreindexedInvalidScoreRecordsRedactedParseFallback() throws Exception {
        Method toDouble = SbertPreindexedReranker.class.getDeclaredMethod("toDouble", Object.class);
        toDouble.setAccessible(true);

        double out = (double) toDouble.invoke(null, RAW_SCORE);

        assertEquals(0.0d, out, 1.0e-9d);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.sbertPreindexed.score.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("agent.sbertPreindexed.score.parseFallback.errorType"));
        assertEquals(RAW_SCORE.length(), TraceStore.get("agent.sbertPreindexed.score.parseFallback.valueLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(RAW_SCORE));
    }

    @Test
    void sbertPreindexedNonFiniteScoreFallsBackToFiniteValue() throws Exception {
        Method toDouble = SbertPreindexedReranker.class.getDeclaredMethod("toDouble", Object.class);
        toDouble.setAccessible(true);

        double out = (double) toDouble.invoke(null, Double.POSITIVE_INFINITY);

        assertEquals(0.0d, out, 1.0e-9d);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.sbertPreindexed.score.parseFallback"));
    }

    @Test
    void sbertPreindexedAnnFailureReturnsOriginalItemsWithRedactedBreadcrumb() {
        SbertPreindexedReranker reranker = new SbertPreindexedReranker(
                (query, k, ef) -> { throw new java.io.IOException("raw private ann path"); },
                text -> new float[] {1.0f, 0.0f});

        List<Map<String, Object>> items = itemsWithInvalidScore();
        List<Map<String, Object>> out = reranker.rerank("private query raw-secret-token", items);

        assertEquals(items, out);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.sbertPreindexed.suppressed"));
        assertEquals("rerank", TraceStore.get("agent.sbertPreindexed.suppressed.stage"));
        assertEquals("IOException", TraceStore.get("agent.sbertPreindexed.suppressed.errorType"));
        assertEquals("private query raw-secret-token".length(),
                TraceStore.get("agent.sbertPreindexed.suppressed.queryLength"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains("private query raw-secret-token"), rendered);
        assertFalse(rendered.contains("raw private ann path"), rendered);
        assertFalse(rendered.contains(RAW_SCORE), rendered);
    }

    @Test
    void sbertPreindexedInvalidEfFallsBackWithRedactedBreadcrumb() {
        int parsed = SbertPreindexedReranker.parseEfOrNprobe("private ef token");

        assertEquals(64, parsed);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.sbertPreindexed.efOrNprobe.suppressed"));
        assertEquals("invalid_number", TraceStore.get("agent.sbertPreindexed.efOrNprobe.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private ef token"));
    }

    @Test
    void sbertPreindexedEfParserAcceptsTrimmedPositiveNumber() {
        int parsed = SbertPreindexedReranker.parseEfOrNprobe(" 32 ");

        assertEquals(32, parsed);
        assertEquals(null, TraceStore.get("agent.sbertPreindexed.efOrNprobe.suppressed"));
    }

    private static List<Map<String, Object>> itemsWithInvalidScore() {
        return itemsWithScore(RAW_SCORE);
    }

    private static List<Map<String, Object>> itemsWithScore(Object score) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "doc-1");
        item.put("title", "alpha");
        item.put("snippet", "beta");
        item.put("score", score);
        return List.of(item);
    }
}
