package com.example.lms.service.onnx;

/** Numerically stable logistic transform with clipping and temperature. */
public final class OnnxScoreUtil {
    private OnnxScoreUtil() {}
    /** Logistic with integer clip (symmetric) and default temperature=1.0 */
    public static double logistic(double logit, int clip) {
        return logistic(logit, clip, 1.0);
    }
    /** Logistic with integer clip (symmetric) and temperature scaling. */
    public static double logistic(double logit, int clip, double temperature) {
        int c = Math.max(1, clip);
        double x = Math.max(-c, Math.min(c, logit)) / Math.max(1e-6, temperature);
        if (x >= 0) {
            double z = Math.exp(-x);
            return 1.0 / (1.0 + z);
        } else {
            double z = Math.exp(x);
            return z / (1.0 + z);
        }
    }
}