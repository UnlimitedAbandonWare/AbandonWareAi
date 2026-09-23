package com.example.lms.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import com.example.lms.search.TraceStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalSigmoidGateTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void riskSignalsReducePassScore() {
        FinalSigmoidGate gate = new FinalSigmoidGate(
                3.0d,
                2.0d,
                1.5d,
                0.5d,
                0.70d,
                "standard",
                "hard");

        double clean = gate.score(0.0d, 0.0d, 0.0d);
        double risky = gate.score(1.0d, 1.0d, 1.0d);

        assertTrue(risky < clean);
        assertTrue(risky < 0.70d);
    }

    @Test
    void nonFiniteRiskIsTreatedAsMaxRisk() {
        FinalSigmoidGate gate = new FinalSigmoidGate(
                3.0d,
                2.0d,
                1.5d,
                0.5d,
                0.70d,
                "standard",
                "hard");

        assertTrue(gate.score(Double.NaN, 0.0d, 0.0d) < gate.score(0.0d, 0.0d, 0.0d));
    }

    @Test
    void aggressiveModeDoesNotPassBelowThresholdWithoutStrongEvidence() {
        FinalSigmoidGate gate = new FinalSigmoidGate(
                3.0d,
                2.0d,
                1.5d,
                0.5d,
                0.70d,
                "aggressive",
                "hard");

        double belowThreshold = gate.score(1.0d, 0.4d, 1.0d);

        assertTrue(belowThreshold < 0.70d);
        assertEquals(FinalSigmoidGate.GateResult.BLOCK,
                gate.check(belowThreshold, 0.4d, false));
    }

    @Test
    void checkRecordsGateResultCountersForReportSummary() {
        FinalSigmoidGate passGate = gate("standard", "hard");
        FinalSigmoidGate blockGate = gate("standard", "hard");
        FinalSigmoidGate warnGate = gate("standard", "soft");
        FinalSigmoidGate degradeGate = gate("standard", "degrade");

        assertEquals(FinalSigmoidGate.GateResult.PASS, passGate.check(0.90d, 0.1d, false));
        assertEquals(FinalSigmoidGate.GateResult.BLOCK, blockGate.check(0.20d, 0.9d, false));
        assertEquals(FinalSigmoidGate.GateResult.WARN, warnGate.check(0.20d, 0.9d, false));
        assertEquals(FinalSigmoidGate.GateResult.DEGRADE, degradeGate.check(0.20d, 0.9d, false));

        assertEquals(1L, TraceStore.getLong("gate.pass.count"));
        assertEquals(1L, TraceStore.getLong("gate.block.count"));
        assertEquals(2L, TraceStore.getLong("gate.warn.count"));
    }

    @Test
    void checkRecordsCompositeScoreAndDecisionContext() {
        FinalSigmoidGate gate = gate("standard", "hard");

        assertEquals(FinalSigmoidGate.GateResult.BLOCK, gate.check(0.42d, 0.33d, false));

        assertEquals(0.42d, ((Number) TraceStore.get("gate.finalSigmoid.compositeScore")).doubleValue(), 1e-9);
        assertEquals(0.33d, ((Number) TraceStore.get("gate.finalSigmoid.policyRisk")).doubleValue(), 1e-9);
        assertEquals(0.70d, ((Number) TraceStore.get("gate.finalSigmoid.threshold")).doubleValue(), 1e-9);
        assertEquals(Boolean.FALSE, TraceStore.get("gate.finalSigmoid.hasStrongEvidence"));
        assertEquals("BLOCK", TraceStore.get("gate.finalSigmoid.result"));
        assertEquals("HARD", TraceStore.get("gate.finalSigmoid.mode"));
        assertEquals(0.42d, ((Number) TraceStore.get("gate.sigmoid.score")).doubleValue(), 1e-9);
        assertEquals(Boolean.FALSE, TraceStore.get("gate.sigmoid.passed"));
        assertEquals(Boolean.FALSE, TraceStore.get("gate.hypernova.override"));
    }

    @Test
    void checkPromotesHypernovaFinalGateWhenActivePathPasses() {
        FinalSigmoidGate gate = gate("standard", "hard");
        TraceStore.put("hypernova.activated", true);

        assertEquals(FinalSigmoidGate.GateResult.PASS, gate.check(0.90d, 0.1d, false));

        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.finalGatePassed"));
    }

    private static FinalSigmoidGate gate(String guardMode, String gateMode) {
        return new FinalSigmoidGate(
                3.0d,
                2.0d,
                1.5d,
                0.5d,
                0.70d,
                guardMode,
                gateMode);
    }
}
