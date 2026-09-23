package com.abandonware.ai.addons.synthesis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoEGateTest {

    @Test
    void constructorTreatsNonFiniteMixAsHeuristicOnly() {
        MoEGate gate = new MoEGate(Double.NaN);

        assertEquals(0.8d, gate.mix(0.8d, 0.2d));
    }

    @Test
    void mixDoesNotPropagateNonFiniteScores() {
        MoEGate gate = new MoEGate(0.5d);

        double mixed = gate.mix(Double.NaN, 0.4d);

        assertTrue(Double.isFinite(mixed));
        assertEquals(0.2d, mixed);
        assertEquals(0.3d, gate.mix(0.6d, Double.POSITIVE_INFINITY));
    }
}
