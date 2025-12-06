package com.abandonware.ai.agent.integrations.math;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.math.ZcaWhitening
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.math.ZcaWhitening
role: config
*/
public class ZcaWhitening {
    public double[] apply(double[] vec){ return vec == null ? new double[0] : vec.clone(); }
}