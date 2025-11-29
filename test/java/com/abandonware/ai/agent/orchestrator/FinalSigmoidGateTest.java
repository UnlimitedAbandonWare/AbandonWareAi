
    package com.abandonware.ai.agent.orchestrator;
    import org.junit.jupiter.api.Test;
    import static org.junit.jupiter.api.Assertions.*;
    public class FinalSigmoidGateTest {
        @Test
        public void boundary() {
            FinalSigmoidGate g = new FinalSigmoidGate(8.0, 0.72, 0.90);
            // a value above x0 should more likely pass
            assertTrue(g.allow(1.0));
        }
    }
    