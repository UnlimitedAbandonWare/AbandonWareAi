
package com.example.lms.fusion;

import java.util.Map;



public class WeightedPowerMeanFusion {

    /** p=1 arithmetic, p=0 geometric (handled as epsilon), p->infty max-like */
    public static double fuse(Map<String,Double> scores, Map<String,Double> weights, double p) {
        double num=0, den=0;
        double eps = 1e-6;
        if (scores.isEmpty()) return 0.0;
        for (Map.Entry<String,Double> e: scores.entrySet()) {
            double w = weights.getOrDefault(e.getKey(), 1.0);
            den += w;
            double s = Math.max(0.0, Math.min(1.0, e.getValue()));
            if (Math.abs(p) < 1e-9) {
                num += w * Math.log(Math.max(eps, s));
            } else {
                num += w * Math.pow(s, p);
            }
        }
        if (Math.abs(p) < 1e-9) {
            return Math.exp(num/Math.max(eps, den));
        } else {
            return Math.pow(Math.max(eps, num/Math.max(eps,den)), 1.0/p);
        }
    }
}