package com.example.lms.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyHyperparamsTest {

    @Test
    void temperatureFallsBackWhenNonFiniteOrNonPositive() {
        StrategyHyperparams hyperparams = new StrategyHyperparams();

        ReflectionTestUtils.setField(hyperparams, "temperature", Double.NaN);
        assertEquals(1.0, hyperparams.temperature());

        ReflectionTestUtils.setField(hyperparams, "temperature", 0.0);
        assertEquals(1.0, hyperparams.temperature());
    }

    @Test
    void epsilonIsClampedToUnitInterval() {
        StrategyHyperparams hyperparams = new StrategyHyperparams();

        ReflectionTestUtils.setField(hyperparams, "epsilon", Double.NaN);
        assertEquals(0.0, hyperparams.epsilon());

        ReflectionTestUtils.setField(hyperparams, "epsilon", -0.5);
        assertEquals(0.0, hyperparams.epsilon());

        ReflectionTestUtils.setField(hyperparams, "epsilon", 1.5);
        assertEquals(1.0, hyperparams.epsilon());
    }
}
