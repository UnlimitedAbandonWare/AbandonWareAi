package com.nova.protocol.fusion;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NovaCvarAggregatorTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void cvarUsesUpperTailFractionForCompatibility() {
        CvarAggregator cvar = new CvarAggregator();

        double out = cvar.cvar(new double[]{0.10d, 0.20d, 0.90d, 0.95d}, 0.25d);

        assertEquals(0.95d, out, 1.0e-9);
        assertEquals(0.25d, (Double) TraceStore.get("hypernova.cvarAlpha"), 1.0e-9);
        assertEquals(0.95d, (Double) TraceStore.get("hypernova.cvarValue"), 1.0e-9);
    }

    @Test
    void upperTailMeanAtQuantileMatchesNovaNextFusionSemantics() {
        double out = CvarAggregator.upperTailMeanAtQuantile(
                List.of(0.10d, 0.20d, 0.90d, 0.95d), 0.75d);

        assertEquals(0.95d, out, 1.0e-9);
    }

    @Test
    void upperTailMeanIgnoresNonFiniteScores() {
        double out = CvarAggregator.upperTailMean(
                List.of(Double.NaN, 0.80d, Double.POSITIVE_INFINITY), 1.0d);

        assertEquals(0.80d, out, 1.0e-9);
    }

    @Test
    void upperTailMeanAtQuantileIgnoresNonFiniteScores() {
        double out = CvarAggregator.upperTailMeanAtQuantile(
                List.of(Double.NaN, 0.40d, 0.90d, Double.NEGATIVE_INFINITY), 0.50d);

        assertEquals(0.90d, out, 1.0e-9);
    }

    @Test
    void upperTailMeanPreservesFiniteRawNegativeScoreDomain() {
        double out = CvarAggregator.upperTailMean(
                List.of(-0.90d, -0.40d, -0.10d), 1.0d);

        assertEquals((-0.90d - 0.40d - 0.10d) / 3.0d, out, 1.0e-9d);
    }

    @Test
    void lowerTailMeanClampsInvalidScoresForRiskDiagnostics() {
        double out = CvarAggregator.lowerTailMean(
                List.of(Double.NaN, -1.0d, 0.40d, 2.0d), 0.50d);

        assertEquals(0.0d, out, 1.0e-9);
    }
}
