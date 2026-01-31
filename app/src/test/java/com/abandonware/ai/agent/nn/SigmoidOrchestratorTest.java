package com.abandonware.ai.agent.nn;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.nn.SigmoidOrchestratorTest
 * Role: config
 * Feature Flags: sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.nn.SigmoidOrchestratorTest
role: config
flags: [sse]
*/
public class SigmoidOrchestratorTest {

    @Test
    void monotonicFinishSigmoid(){
        double s1 = SigmoidOrchestrator.sigmoidFinish(-2, 1.2, 0.0);
        double s2 = SigmoidOrchestrator.sigmoidFinish( 0, 1.2, 0.0);
        double s3 = SigmoidOrchestrator.sigmoidFinish( 2, 1.2, 0.0);
        assertTrue(s1 < s2 && s2 < s3);
        assertTrue(s1 >= 0 && s3 <= 1);
    }

    @Test
    void fingerWithinBounds(){
        double s = SigmoidOrchestrator.sigmoidFinger(0.5, 1.0, 0.0, 0.25);
        assertTrue(s >= 0 && s <= 1);
    }
}