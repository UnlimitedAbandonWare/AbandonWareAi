package com.abandonware.ai.agent.orchestrator.recovery;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VerdictTest {

    @Test
    void constructorFailsSafeForNonFiniteConfidence() {
        Verdict nan = new Verdict(Verdict.Decision.RECOVER, FailureClass.UNKNOWN,
                Double.NaN, "bad", Map.of());
        Verdict positiveInfinity = Verdict.accept(Double.POSITIVE_INFINITY, "too high");
        Verdict negativeInfinity = new Verdict(Verdict.Decision.RECOVER, FailureClass.DATA,
                Double.NEGATIVE_INFINITY, "too low", Map.of());

        assertFalse(Double.isNaN(nan.confidence()));
        assertEquals(0.0d, nan.confidence());
        assertEquals(1.0d, positiveInfinity.confidence());
        assertEquals(0.0d, negativeInfinity.confidence());
    }
}
