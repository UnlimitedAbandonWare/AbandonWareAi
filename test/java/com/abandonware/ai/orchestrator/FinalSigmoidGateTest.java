package com.abandonware.ai.orchestrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FinalSigmoidGateTest {
    @Test
    void boundary() {
        // threshold 0.90, strict 모드에서 compositeScore 기준 동작 확인
        FinalSigmoidGate g = new FinalSigmoidGate(0.90, "strict");
        assertFalse(g.allow(0.10));
        assertTrue(g.allow(1.0));
    }
}
