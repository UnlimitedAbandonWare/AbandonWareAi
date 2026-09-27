package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StochasticCircuitBreakerBoundaryTest {
    private static final long ZERO_ROLL_SALT = 1125899906842597L ^ Long.MIN_VALUE;

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    @Test
    void zeroCalculatedProbabilityNeverExploresEvenWhenRollIsZero() {
        StochasticCircuitBreaker breaker = new StochasticCircuitBreaker(0.55d, ZERO_ROLL_SALT);
        assertFalse(breaker.shouldExplore(0d, ""));
    }

    @Test
    void invalidRiskClampedToZeroCannotConsumeAnExplorationAttempt() {
        StochasticCircuitBreaker breaker = new StochasticCircuitBreaker(0.55d, ZERO_ROLL_SALT);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertFalse(breaker.shouldExplore(-1d, "")),
                () -> assertFalse(breaker.shouldExplore(Double.NaN, "")),
                () -> assertFalse(breaker.shouldExplore(Double.POSITIVE_INFINITY, "")));
    }

    @Test
    void positiveProbabilityAndExplicitZeroThresholdStillAllowZeroRoll() {
        assertTrue(new StochasticCircuitBreaker(0.55d, ZERO_ROLL_SALT).shouldExplore(1d, ""));
        assertTrue(new StochasticCircuitBreaker(0d, ZERO_ROLL_SALT).shouldExplore(0d, ""));
    }
}
