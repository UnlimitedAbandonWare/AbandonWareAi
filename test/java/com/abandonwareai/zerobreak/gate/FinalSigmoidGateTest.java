package com.abandonwareai.zerobreak.gate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FinalSigmoidGateTest {
    @Test
    void allowWhenCompositeScoreAboveThreshold() {
        FinalSigmoidGate g = new FinalSigmoidGate(0.90, "strict");
        double scoreHigh = g.score(0.1, 0.1, 0.1);

        assertTrue(scoreHigh > 0.90);
        assertTrue(g.allow(scoreHigh));
    }

    @Test
    void rejectWhenBelowThreshold() {
        FinalSigmoidGate g = new FinalSigmoidGate(0.90, "strict");
        double scoreLow = g.score(1.0, 1.0, 1.0);

        assertTrue(scoreLow < 0.90);
        assertFalse(g.allow(scoreLow));
    }
}
