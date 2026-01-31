package com.nova.protocol.fusion;

import java.util.List;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.fusion.TailWeightedPowerMeanFuser
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.fusion.TailWeightedPowerMeanFuser
role: config
*/
public class TailWeightedPowerMeanFuser {

    public double fuse(List<Double> scores, double pBase, double tailQuantile) {
        if (scores == null || scores.isEmpty()) return 0.0;
        scores = scores.stream().filter(d -> d != null && !d.isNaN() && !d.isInfinite()).toList();
        if (scores.isEmpty()) return 0.0;

        // sort descending
        var sorted = scores.stream().sorted((a,b)->Double.compare(b,a)).toList();
        int tailCount = Math.max(1, (int)Math.ceil(sorted.size() * tailQuantile));
        var tail = sorted.subList(0, tailCount);
        double tailMean = tail.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        // dynamic exponent p increases with tail prominence
        double p = Math.max(1.0, pBase * (1.0 + tailMean));
        double num = 0.0, den = 0.0;
        for (double s : scores) {
            double sp = Math.pow(Math.max(0.0, s), p);
            num += sp;
            den += 1.0;
        }
        return den == 0.0 ? 0.0 : Math.pow(num / den, 1.0 / p);
    }
}