package com.example.lms.service.rag.fusion;

public final class PlattIsotonicCalibrator {
    private final double a;
    private final double b;
    public PlattIsotonicCalibrator(double a, double b){
        this.a = a; this.b = b;
    }
    /** Simple Platt scaling with tanh soft clamp to attenuate outliers. */
    public double normalize(double raw){
        double y = (a * raw) + b;
        double p = 1.0 / (1.0 + Math.exp(-y));
        return com.example.lms.service.rag.fusion.BodeClamp.clamp(p, 0.0, 4.0);
    }
}