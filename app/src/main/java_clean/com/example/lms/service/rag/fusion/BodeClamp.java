package com.example.lms.service.rag.fusion;

public final class BodeClamp {
    private BodeClamp(){}
    /** Clamp x to [lo,hi] then soften with tanh (Bode-like attenuation). */
    public static double clamp(double x, double lo, double hi){
        if (Double.isNaN(x)) return 0.0;
        if (Double.isNaN(lo) || Double.isInfinite(lo)) lo = -1e9;
        if (Double.isNaN(hi) || Double.isInfinite(hi)) hi =  1e9;
        double v = Math.max(lo, Math.min(hi, x));
        return Math.tanh(v);
    }
    /** Element-wise clamp and soften. */
    public static double[] clamp(double[] xs, double lo, double hi){
        if (xs == null) return new double[0];
        double[] out = new double[xs.length];
        for (int i=0;i<xs.length;i++) out[i] = clamp(xs[i], lo, hi);
        return out;
    }
}