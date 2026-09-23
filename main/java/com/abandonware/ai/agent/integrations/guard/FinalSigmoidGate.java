package com.abandonware.ai.agent.integrations.guard;

public final class FinalSigmoidGate {
    public boolean allow(double fusedScore, double rerankScore, double authority, double k, double x0) {
        double x = 0.5*fusedScore + 0.35*rerankScore + 0.15*authority;
        double s = 1.0 / (1.0 + Math.exp(-k*(x - x0)));
        return s >= 0.90;
    }
    public double score(double fusedScore, double rerankScore, double authority, double k, double x0) {
        double x = 0.5*fusedScore + 0.35*rerankScore + 0.15*authority;
        return 1.0 / (1.0 + Math.exp(-k*(x - x0)));
    }
}