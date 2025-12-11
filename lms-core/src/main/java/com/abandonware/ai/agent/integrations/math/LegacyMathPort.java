package com.abandonware.ai.agent.integrations.math;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.math.LegacyMathPort
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.math.LegacyMathPort
role: config
*/
public class LegacyMathPort {
    public static double clamp(double x, double lo, double hi){ return Math.max(lo, Math.min(hi, x)); }
    public static double sigmoid(double x){ return 1.0 / (1.0 + Math.exp(-x)); }
    public static double safePow(double x, double p){ try { return Math.pow(x, p); } catch(Exception e){ return 0.0; } }
}