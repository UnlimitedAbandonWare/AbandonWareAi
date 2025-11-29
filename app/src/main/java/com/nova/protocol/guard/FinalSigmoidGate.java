package com.nova.protocol.guard;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.guard.FinalSigmoidGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.guard.FinalSigmoidGate
role: config
*/
public class FinalSigmoidGate {
    /**
     * @param x  final fused score (0..1 nominal)
     * @param k  sensitivity (steepness)
     * @param x0 threshold center
     * @param passProb minimum probability to pass (e.g., 0.90)
     */
    public boolean pass(double x, double k, double x0, double passProb) {
        double s = 1.0 / (1.0 + Math.exp(-k * (x - x0)));
        return s >= passProb;
    }


    // [PATCH] Final Sigmoid gating
    public boolean allowAnswer(double crossScore) {
        if (!this.enabled) return true;
        double k = this.k != 0.0 ? this.k : 12.0;
        double x0 = this.x0;
        double prob = 1.0/(1.0 + Math.exp(-k*(crossScore - x0)));
        return prob >= 0.5;
    }

}