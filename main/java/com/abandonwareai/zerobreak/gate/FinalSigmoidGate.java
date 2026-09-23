package com.abandonwareai.zerobreak.gate;

/** Final quality gate based on sigmoid confidence. */
public class FinalSigmoidGate {
    private final double threshold;
    private final double k;   // sensitivity
    private final double x0;  // finish (midpoint)

    public FinalSigmoidGate(double threshold, double k, double x0) {
        this.threshold = threshold;
        this.k = k;
        this.x0 = x0;
    }
    public boolean approve(double x) {
        return sigmoid(x) >= threshold;
    }
    public double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-k * (x - x0)));
    }
}