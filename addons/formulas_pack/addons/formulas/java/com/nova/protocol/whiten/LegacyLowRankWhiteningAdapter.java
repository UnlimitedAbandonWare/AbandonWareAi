package com.nova.protocol.whiten;

import java.util.Arrays;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.whiten.LegacyLowRankWhiteningAdapter
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.whiten.LegacyLowRankWhiteningAdapter
role: config
*/
public class LegacyLowRankWhiteningAdapter {

    public double[] apply(double[] v, double eps) {
        if (v == null || v.length == 0) return new double[0];
        double mean = 0.0;
        for (double x : v) mean += x;
        mean /= v.length;
        double var = 0.0;
        for (double x : v) var += (x - mean)*(x - mean);
        var /= v.length;
        double scale = 1.0 / Math.sqrt(var + eps);
        double[] out = Arrays.copyOf(v, v.length);
        for (int i = 0; i < out.length; i++) out[i] = (out[i] - mean) * scale;
        return out;
    }
}