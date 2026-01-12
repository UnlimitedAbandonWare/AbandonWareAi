package com.abandonwareai.fusion;

import org.springframework.stereotype.Component;

/**
 * Weighted Power Mean (generalised mean).
 * p=1 → arithmetic, p→0 → geometric (handled as epsilon),
 * p→-∞ → min-like, p→+∞ → max-like (approx with large |p|).
 * Inputs are assumed in [0,1]. We clamp to [0,1] for numerical safety.
 */
@Component
public class WeightedPowerMean {
    public double combine(double[] scores, double[] weights, double p) {
        if (scores == null || weights == null || scores.length == 0 || weights.length != scores.length) return 0.0;
        double eps = 1e-9;
        double wsum = 0.0;
        double acc  = 0.0;
        if (Math.abs(p) < 1e-9) {
            // Geometric mean with weights: exp( sum_i w_i * log(max(eps, s_i)) / sum_i w_i )
            for (int i=0;i<scores.length;i++) {
                double s = Math.max(0.0, Math.min(1.0, scores[i]));
                double w = (i < weights.length) ? Math.max(0.0, weights[i]) : 1.0;
                wsum += w;
                acc  += w * Math.log(Math.max(eps, s));
            }
            if (wsum < eps) return 0.0;
            return Math.exp(acc / wsum);
        } else {
            for (int i=0;i<scores.length;i++) {
                double s = Math.max(0.0, Math.min(1.0, scores[i]));
                double w = (i < weights.length) ? Math.max(0.0, weights[i]) : 1.0;
                wsum += w;
                acc  += w * Math.pow(s, p);
            }
            if (wsum < eps) return 0.0;
            double mean = acc / wsum;
            return Math.pow(Math.max(eps, mean), 1.0/p);
        }
    }
}