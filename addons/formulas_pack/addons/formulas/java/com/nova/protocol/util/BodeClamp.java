package com.nova.protocol.util;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.util.BodeClamp
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.util.BodeClamp
role: config
*/
public class BodeClamp {
    public double clamp(double x, double gain) {
        // Soft saturation using tanh-like curve
        double y = Math.tanh(gain * x);
        // map back to [0,1] if input is in [0,1]; otherwise return y as-is for generic use
        return Math.max(0.0, Math.min(1.0, (y + 1.0)/2.0));
    }
}