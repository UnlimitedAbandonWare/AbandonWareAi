package com.example.lms.service.rag.fusion;

import java.util.Map;

/** Score calibration hooks for per-query normalization. */
public interface FusionCalibrator {

    /** Min-max scale scores within a single query. */
    Map<String, Double> minMaxPerQuery(Map<String, Double> scores);

    /** Isotonic regression based calibration using a pre-trained model descriptor. */
    Map<String, Double> isotonicPerQuery(Map<String, Double> scores, IsotonicModel model);

    /** Minimal model descriptor placeholder to avoid heavy deps. */
    final class IsotonicModel {
        public final double[] x; public final double[] y;
        public IsotonicModel(double[] x, double[] y) { this.x = x; this.y = y; }
    }

    /** Convenience: in-place safe min-max scaling for raw arrays (returns new array). */
    static double[] minMax(double[] scores) {
        if (scores == null || scores.length == 0) return scores;
        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
        for (double v : scores) { if (v < mn) mn = v; if (v > mx) mx = v; }
        double[] out = new double[scores.length];
        if (!(mx > mn)) {
            // All equal or NaN; map to zeros
            for (int i=0;i<out.length;i++) out[i] = 0.0;
            return out;
        }
        double rng = mx - mn;
        for (int i=0;i<out.length;i++) {
            double v = scores[i];
            out[i] = (v - mn) / rng;
        }
        return out;
    }

}