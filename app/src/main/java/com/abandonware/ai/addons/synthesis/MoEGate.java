package com.abandonware.ai.addons.synthesis;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.synthesis.MoEGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.addons.synthesis.MoEGate
role: config
*/
public class MoEGate {
    private final double mix;
    public MoEGate(double mix) { this.mix = Math.max(0, Math.min(1, mix)); }
    public double mix(double heuristic, double dynamic) {
        return (1.0 - mix) * heuristic + mix * dynamic;
    }
}