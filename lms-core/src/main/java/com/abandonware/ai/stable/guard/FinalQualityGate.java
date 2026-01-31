package com.abandonware.ai.stable.guard;

/** Final sigmoid-like gate; returns true if score passes threshold. */
public final class FinalQualityGate {
    private final double k;
    private final double x0;
    private final double tau;

    public FinalQualityGate(){ this(8.0, 0.62, 0.90); }
    public FinalQualityGate(double k, double x0, double tau){
        this.k = k; this.x0 = x0; this.tau = tau;
    }

    public boolean pass(double fusedScore, int citationCount){
        double s = 1.0/(1.0+Math.exp(-k*(fusedScore - x0)));
        return s >= tau && citationCount >= 3;
    }
}