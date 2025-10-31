package com.example.lms.util;


/**
 * Shared logistic sigmoid normalizer used across scoring components.
 * Default shift=3.0, scale=2.0 to map small raw scores into a 0..1 band.
 */
public final class ScoreUtils {
    private ScoreUtils() {}

    public static double logisticSigmoid(double x) {
        return logisticSigmoid(x, 3.0, 2.0);
    }

    public static double logisticSigmoid(double x, double shift, double scale) {
        return 1.0 / (1.0 + Math.exp(-(x - shift) / scale));
    }
}