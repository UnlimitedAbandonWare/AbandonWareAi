package com.abandonware.patch.guard;

public class FinalSigmoidGate {
    private final double k, x0;
    private double threshold = 0.90; // pass9x
    public FinalSigmoidGate(double k, double x0) { this.k = k; this.x0 = x0; }
    public void setThreshold(double t) { this.threshold = t; }
    public boolean pass(double x) {
        double s = 1.0 / (1.0 + Math.exp(-k * (x - x0)));
        return s >= threshold;
    }
}