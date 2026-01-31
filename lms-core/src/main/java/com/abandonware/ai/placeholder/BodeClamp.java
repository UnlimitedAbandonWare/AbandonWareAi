package com.abandonware.ai.placeholder;

/** Simple tanh-based clamp to dampen sensitivity before fusion. */
public final class BodeClamp {
    private BodeClamp() {}
    public static double clamp(double x, double alpha) {
        double a = Math.max(0.01, alpha);
        return Math.tanh(a * x);
    }
}