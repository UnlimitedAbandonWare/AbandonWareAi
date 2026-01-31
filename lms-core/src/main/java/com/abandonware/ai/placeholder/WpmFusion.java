package com.abandonware.ai.placeholder;

import java.util.List;

/** Weighted Power Mean (WPM) fusion helper. */
public final class WpmFusion {
    private WpmFusion() {}
    public static double mean(double[] xs, double[] ws, double p) {
        if (xs == null || ws == null || xs.length == 0 || xs.length != ws.length) return Double.NaN;
        double sum = 0.0, wsum = 0.0;
        for (int i=0;i<xs.length;i++){
            double x = Math.max(0.0, xs[i]);
            double w = Math.max(0.0, ws[i]);
            sum += w * Math.pow(x, p);
            wsum += w;
        }
        if (wsum == 0.0) return Double.NaN;
        return Math.pow(sum / wsum, 1.0 / p);
    }
}