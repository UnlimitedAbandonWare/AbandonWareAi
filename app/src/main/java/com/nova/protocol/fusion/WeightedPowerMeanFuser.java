package com.nova.protocol.fusion;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.fusion.WeightedPowerMeanFuser
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.fusion.WeightedPowerMeanFuser
role: config
*/
public class WeightedPowerMeanFuser {
    /** M_p = (sum_i w_i * x_i^p / sum_i w_i)^(1/p) */
    public double fuse(double[] scores, double[] weights, double p) {
        if (scores == null || weights == null || scores.length == 0 || scores.length != weights.length) {
            return 0.0;
        }
        double num = 0.0, den = 0.0;
        for (int i = 0; i < scores.length; i++) {
            num += weights[i] * Math.pow(scores[i], p);
            den += weights[i];
        }
        if (den == 0.0) return 0.0;
        return Math.pow(num / den, 1.0 / p);
    }
}