package com.example.lms.service.rag.consensus;

import com.example.lms.search.probe.BranchQualityProbe;
import com.example.lms.search.probe.EvidenceSignals;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreeLaneRiskConsensusTest {

    @Test
    void highRiskLowersAdjustedWeight() {
        ThreeLaneRiskConsensus.Config cfg =
                new ThreeLaneRiskConsensus.Config(true, 0.45d, 2, 0.05d, 1.25d);
        EvidenceSignals.LaneEvidenceSignals signal = signal("RC", 0.90d, 0.80d, 0.20d);
        BranchQualityProbe.BranchQualityMetrics metric = metric("RC", 0.90d);

        ThreeLaneRiskConsensus.Decision decision =
                ThreeLaneRiskConsensus.evaluate("RC", signal, metric, 1.0d, 0.80d, cfg);

        assertTrue(decision.enabled());
        assertEquals("EXPLORE", decision.role());
        assertTrue(decision.laneRisk() > 0.60d);
        assertTrue(decision.adjustedRrfWeight() < 1.0d);
        assertTrue(decision.tracePayload().containsKey("laneRdi"));
    }

    @Test
    void disabledKeepsBaseWeightClamped() {
        ThreeLaneRiskConsensus.Config cfg =
                new ThreeLaneRiskConsensus.Config(false, 0.45d, 2, 0.05d, 1.25d);

        ThreeLaneRiskConsensus.Decision decision =
                ThreeLaneRiskConsensus.evaluate("BQ", null, null, 2.5d, 1.0d, cfg);

        assertFalse(decision.enabled());
        assertEquals("STRICT", decision.role());
        assertEquals(1.25d, decision.adjustedRrfWeight());
    }

    @Test
    void missingSignalsFailSoftAndRespectClamp() {
        ThreeLaneRiskConsensus.Config cfg =
                new ThreeLaneRiskConsensus.Config(true, 0.45d, 2, 0.20d, 1.35d);

        ThreeLaneRiskConsensus.Decision decision =
                ThreeLaneRiskConsensus.evaluate("ER", null, null, 0.01d, 0.0d, cfg);

        assertTrue(decision.enabled());
        assertEquals("RELAXED", decision.role());
        assertEquals(0.20d, decision.adjustedRrfWeight());
    }

    private static EvidenceSignals.LaneEvidenceSignals signal(
            String lane,
            double duplicate,
            double contradiction,
            double authority) {
        return new EvidenceSignals.LaneEvidenceSignals(
                lane,
                "role",
                3,
                0.8d,
                0.6d,
                2,
                duplicate,
                contradiction,
                authority,
                0.1d,
                0.5d,
                0.5d,
                0.2d);
    }

    private static BranchQualityProbe.BranchQualityMetrics metric(String lane, double risk) {
        return new BranchQualityProbe.BranchQualityMetrics(
                lane,
                "role",
                3,
                0.2d,
                0.7d,
                0.5d,
                0.5d,
                risk,
                1,
                1.0d,
                1.0d,
                3,
                0.2d,
                0.7d,
                BranchQualityProbe.BranchAction.PASS,
                "ok");
    }
}
