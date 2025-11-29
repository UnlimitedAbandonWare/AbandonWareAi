package com.abandonwareai.zerobreak.gate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FinalSigmoidGateTest {
    @Test
    void approveWhenAboveThreshold() {
        FinalSigmoidGate g = new FinalSigmoidGate(0.90, 12.0, 0.65);
        assertTrue(g.approve(0.80)); // sigmoid(0.8) with k=12, x0=0.65 ≈ 0.90+
    }
    @Test
    void rejectWhenBelow() {
        FinalSigmoidGate g = new FinalSigmoidGate(0.90, 12.0, 0.65);
        assertFalse(g.approve(0.60));
    }
}