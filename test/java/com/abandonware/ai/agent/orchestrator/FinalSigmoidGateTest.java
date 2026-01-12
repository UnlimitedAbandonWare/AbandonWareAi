package com.abandonware.ai.agent.orchestrator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FinalSigmoidGateTest {
    @Test
    public void boundary() {
        // threshold 0.90, strict 모드: compositeScore 가 threshold 이상일 때만 통과
        FinalSigmoidGate g = new FinalSigmoidGate(0.90, "strict");
        assertFalse(g.allow(0.10));
        assertTrue(g.allow(1.0));
    }
}
