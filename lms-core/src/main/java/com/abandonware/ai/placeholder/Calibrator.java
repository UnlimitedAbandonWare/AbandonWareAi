package com.abandonware.ai.placeholder;

/** Sigmoid calibrator as a safe default when isotonic is unavailable. */
public final class Calibrator {
    private Calibrator() {}
    public static double sigmoid(double x, double a, double b) {
        return 1.0 / (1.0 + Math.exp(-(a * x + b)));
    }
}