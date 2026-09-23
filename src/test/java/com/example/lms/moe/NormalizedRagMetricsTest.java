package com.example.lms.moe;

import com.example.lms.metrics.FaithfulnessMetricSnapshotStore;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizedRagMetricsTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        FaithfulnessMetricSnapshotStore.clear();
    }

    @Test
    void normalizesRagMetricsWithBoundedDiversityAndCosts() {
        NormalizedRagMetrics metrics = NormalizedRagMetrics.from(
                2,
                1,
                3,
                2,
                Map.of("WEB", 2, "VECTOR", 1),
                1.5d,
                3,
                0.0d,
                0.5d);

        assertEquals(0.5d, metrics.retrievalHitRate(), 1e-9);
        assertEquals(2.0d / 3.0d, metrics.evidenceCoverage(), 1e-9);
        assertEquals(8.0d / 9.0d, metrics.sourceDiversity(), 1e-9);
        assertEquals(0.5d, metrics.resultDepth(), 1e-9);
        assertEquals(0.0d, metrics.latencyCost(), 1e-9);
        assertEquals(1.0d, metrics.latencyQuality(), 1e-9);
        assertEquals(0.5d, metrics.fallbackCost(), 1e-9);
        assertEquals(0.5d, metrics.fallbackStability(), 1e-9);
        assertTrue(metrics.balancedScore() > 0.67d && metrics.balancedScore() < 0.68d);
    }

    @Test
    void clipsInvalidAndOutOfRangeInputs() {
        NormalizedRagMetrics metrics = NormalizedRagMetrics.fromRates(
                2.0d,
                -1.0d,
                Double.NaN,
                4.0d,
                30_000.0d,
                3.0d);

        assertEquals(1.0d, metrics.retrievalHitRate(), 1e-9);
        assertEquals(0.0d, metrics.evidenceCoverage(), 1e-9);
        assertEquals(0.0d, metrics.sourceDiversity(), 1e-9);
        assertEquals(1.0d, metrics.resultDepth(), 1e-9);
        assertEquals(1.0d, metrics.fallbackCost(), 1e-9);
        assertEquals(0.0d, metrics.fallbackStability(), 1e-9);
        assertTrue(metrics.latencyCost() > 0.99d && metrics.latencyCost() <= 1.0d);
    }

    @Test
    void thresholdBreaksUseSharedDefaultThresholdsAndSeverity() {
        NormalizedRagMetrics metrics = NormalizedRagMetrics.fromRates(
                0.20d,
                0.50d,
                0.10d,
                0.10d,
                7_000.0d,
                0.80d);

        List<Map<String, Object>> breaks = NormalizedRagMetrics.thresholdBreaks(metrics, 4);

        assertTrue(breaks.stream().anyMatch(row -> "retrieval_starvation".equals(row.get("label"))
                && "retrieval".equals(row.get("stage"))
                && ((Number) row.get("severity")).doubleValue() == 0.40d));
        assertTrue(breaks.stream().anyMatch(row -> "source_collapse".equals(row.get("label"))));
        assertTrue(breaks.stream().anyMatch(row -> "latency_pressure".equals(row.get("label"))));
        assertTrue(breaks.stream().anyMatch(row -> "fallback_dependency".equals(row.get("label"))));
    }

    @Test
    void thresholdBreaksEmptyForHealthyMetrics() {
        NormalizedRagMetrics metrics = NormalizedRagMetrics.fromRates(
                0.95d,
                0.90d,
                0.80d,
                0.50d,
                100.0d,
                0.0d);

        assertTrue(NormalizedRagMetrics.thresholdBreaks(metrics, 4).isEmpty());
    }

    @Test
    void putTracePublishesCanonicalFaithfulnessKeysAndKeepsLegacyAliases() {
        NormalizedRagMetrics metrics = NormalizedRagMetrics.fromRates(
                0.95d,
                0.90d,
                0.80d,
                0.50d,
                100.0d,
                0.10d);

        metrics.putTrace();

        assertEquals(0.95d, (Double) TraceStore.get("rag.eval.normalized.retrievalHitRate"), 1e-9);
        assertEquals(0.90d, (Double) TraceStore.get("rag.eval.normalized.evidenceCoverage"), 1e-9);
        assertEquals(0.80d, (Double) TraceStore.get("rag.eval.normalized.sourceDiversity"), 1e-9);
        assertEquals(0.50d, (Double) TraceStore.get("rag.eval.normalized.resultDepth"), 1e-9);
        assertTrue((Double) TraceStore.get("rag.eval.normalized.latencyCost") > 0.0d);
        assertEquals(0.10d, (Double) TraceStore.get("rag.eval.normalized.fallbackCost"), 1e-9);
        assertTrue((Double) TraceStore.get("rag.eval.normalized.balancedScore") > 0.70d);
        assertEquals(NormalizedRagMetrics.SCHEMA_VERSION,
                TraceStore.get("rag.eval.normalized.schemaVersion"));

        assertEquals(0.95d, (Double) TraceStore.get("rag.metrics.normalized.retrievalHitRate"), 1e-9);
        assertEquals(0.90d, (Double) TraceStore.get("rag.metrics.normalized.evidenceCoverage"), 1e-9);
        assertEquals(0.80d, (Double) TraceStore.get("rag.metrics.normalized.sourceDiversity"), 1e-9);
        assertEquals(0.50d, (Double) TraceStore.get("rag.metrics.normalized.resultDepth"), 1e-9);
        assertTrue((Double) TraceStore.get("rag.metrics.normalized.latencyCost") > 0.0d);
        assertEquals(0.10d, (Double) TraceStore.get("rag.metrics.normalized.fallbackCost"), 1e-9);
        assertTrue((Double) TraceStore.get("rag.metrics.normalized.balancedScore") > 0.70d);
        assertEquals(7L, TraceStore.getAll().keySet().stream()
                .filter(key -> key.startsWith("rag.metrics.normalized."))
                .count());
    }
}
