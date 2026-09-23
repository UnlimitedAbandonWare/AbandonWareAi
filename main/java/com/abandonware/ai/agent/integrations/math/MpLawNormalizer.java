package com.abandonware.ai.agent.integrations.math;


/**
 * MP-Law normalizer (placeholder).
 * Replace with legacy port. Keeps monotonic normalization to stabilize scores.
 */
public class MpLawNormalizer {
    public double normalize(double x){
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0.0;
        // simple rank-preserving squashing
        return Math.tanh(x);
    }
}