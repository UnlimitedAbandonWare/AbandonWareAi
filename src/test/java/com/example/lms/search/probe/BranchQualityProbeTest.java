package com.example.lms.search.probe;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchQualityProbeTest {

    private final BranchQualityProbe probe = new BranchQualityProbe();
    private final BranchQualityProbe.Thresholds thresholds =
            new BranchQualityProbe.Thresholds(0.35d, 0.65d, 0.65d, 0.70d, 0.35d, 0.85d, 4, 0.20d);

    @Test
    void healthyDomainBranchGetsHighTileAndPassAction() {
        EvidenceSignals.LaneEvidenceSignals signal = new EvidenceSignals.LaneEvidenceSignals(
                "BQ",
                "domain_definition",
                4,
                1.0d,
                1.0d,
                3,
                0.0d,
                0.0d,
                0.85d,
                0.80d,
                0.90d,
                0.88d,
                0.70d);

        BranchQualityProbe.BranchQualityMetrics metric = probe.evaluateLane(signal, thresholds);

        assertEquals("BQ", metric.branchId());
        assertEquals("domain_definition", metric.intentAxis());
        assertEquals(3, metric.matrixTile());
        assertEquals(BranchQualityProbe.BranchAction.PASS, metric.action());
        assertTrue(metric.contextContribution() > metric.riskPenalty());
    }

    @Test
    void duplicateHeavyAliasBranchShrinksAndLowersDppLambda() {
        EvidenceSignals.LaneEvidenceSignals signal = new EvidenceSignals.LaneEvidenceSignals(
                "ER",
                "alias_synonym",
                5,
                1.0d,
                0.40d,
                1,
                0.80d,
                0.05d,
                0.55d,
                0.40d,
                0.50d,
                0.45d,
                0.20d);

        BranchQualityProbe.BranchQualityMetrics metric = probe.evaluateLane(signal, thresholds);

        assertEquals("ER", metric.branchId());
        assertEquals(BranchQualityProbe.BranchAction.SHRINK, metric.action());
        assertTrue(metric.adjustedTopK() < thresholds.baseTopK());
        assertTrue(metric.dppLambda() < thresholds.baseDppLambda());
    }

    @Test
    void diversityPressureStaysLowForHealthyBranchAndHighForDuplicateBranch() {
        BranchQualityProbe.BranchQualityMetrics healthy = probe.evaluateLane(
                new EvidenceSignals.LaneEvidenceSignals("BQ", "domain_definition", 4, 1.0d, 1.0d, 3,
                        0.0d, 0.0d, 0.90d, 0.80d, 0.90d, 0.90d, 0.70d), thresholds);
        BranchQualityProbe.BranchQualityMetrics duplicate = probe.evaluateLane(
                new EvidenceSignals.LaneEvidenceSignals("ER", "alias_synonym", 5, 1.0d, 0.40d, 1,
                        0.80d, 0.05d, 0.55d, 0.40d, 0.50d, 0.45d, 0.20d), thresholds);

        assertTrue(probe.diversityPressure(java.util.List.of(healthy)) < 0.20d);
        assertTrue(probe.diversityPressure(java.util.List.of(duplicate)) >= 0.20d);
    }

    @Test
    void moeWeightReducesWeakBranchAgainstHealthyBranch() {
        Map<String, BranchQualityProbe.BranchQualityMetrics> metrics = probe.evaluateLanes(Map.of(
                "BQ", new EvidenceSignals.LaneEvidenceSignals("BQ", "domain_definition", 4, 1.0d, 1.0d, 3,
                        0.0d, 0.0d, 0.90d, 0.80d, 0.90d, 0.90d, 0.70d),
                "RC", new EvidenceSignals.LaneEvidenceSignals("RC", "relation_hypothesis", 1, 1.0d, 0.0d, 0,
                        0.90d, 0.20d, 0.30d, 0.0d, 0.20d, 0.10d, 0.0d)), thresholds);

        assertTrue(metrics.get("BQ").rrfWeight() > metrics.get("RC").rrfWeight());
        assertEquals(BranchQualityProbe.BranchAction.REWRITE_RETRY, metrics.get("RC").action());
    }

    @Test
    void attemptMetricTriggersSingleBranchRetryShapeWithoutRawPayload() {
        BranchQualityProbe.BranchQualityMetrics metric = probe.evaluateAttempt(
                "RC", 6, 3, 0, 1.0d, 0.30d, false, "none", thresholds);

        assertEquals("RC", metric.branchId());
        assertEquals("relation_hypothesis", metric.intentAxis());
        assertEquals(BranchQualityProbe.BranchAction.REWRITE_RETRY, metric.action());
        assertTrue(metric.shouldRetry());
        assertTrue(metric.tracePayload().containsKey("riskPenalty"));
    }

    @Test
    void nonStandardLaneWithSecretBecomesHashLabel() {
        String rawLane = "custom-lane token=test-secret-abcdefghijklmnop";

        BranchQualityProbe.BranchQualityMetrics metric = probe.evaluateAttempt(
                rawLane, 6, 3, 1, 1.0d, 0.30d, false, "none", thresholds);

        String payload = String.valueOf(metric.tracePayload());
        assertTrue(metric.branchId().startsWith("hash:"), metric.branchId());
        assertFalse(payload.contains("test-secret-abcdefghijklmnop"), payload);
        assertFalse(payload.contains(rawLane), payload);
    }

    @Test
    void tracePayloadRedactsReasonDiagnostics() {
        String secretShapedReason = "retry because api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        BranchQualityProbe.BranchQualityMetrics metric = new BranchQualityProbe.BranchQualityMetrics(
                "RC", "relation_hypothesis", 1, 0.0d, 0.0d, 0.0d, 0.0d, 1.0d,
                0, 0.0d, 0.0d, 1, 0.2d, 0.7d,
                BranchQualityProbe.BranchAction.REWRITE_RETRY, secretShapedReason);

        String renderedReason = String.valueOf(metric.tracePayload().get("reason"));

        assertTrue(renderedReason.startsWith("hash:"), renderedReason);
        assertFalse(renderedReason.contains("sk-"), renderedReason);
    }
}
