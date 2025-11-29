package com.abandonware.ai.agent.integrations.math;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.math.MpLawNormalizer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.math.MpLawNormalizer
role: config
*/
public class MpLawNormalizer {
    public double normalize(double x){
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0.0;
        // simple rank-preserving squashing
        return Math.tanh(x);
    }
}