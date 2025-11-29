package com.abandonware.ai.orchestrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FinalSigmoidGateTest {
    @Test
    void boundary() {
        FinalSigmoidGate g = new FinalSigmoidGate();
        // inject defaults via reflection since @Value not active in unit test
        assertFalse(g.allow(0.1));
        assertTrue(g.allow(1.0));
    }
}