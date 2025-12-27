package com.example.guard;

import org.springframework.stereotype.Component;



@Component
public class FinalQualityGate {

    public record Signals(
        int evidenceCount,
        double authorityTierScore,
        double contradictionScore,
        double reproducibilityScore
    ) { }

    public record GateProps(double wEv, double wAuth, double wContra, double wReprod,
                            double k, double x0, double passThreshold, int minEvidence) { }

    public boolean pass(Signals s, GateProps p) {
        double x = p.wEv() * clip01(normCount(s.evidenceCount()))
                 + p.wAuth() * clip01(s.authorityTierScore())
                 + p.wReprod() * clip01(s.reproducibilityScore())
                 - p.wContra() * clip01(s.contradictionScore());
        double sigmoid = 1.0 / (1.0 + Math.exp(-p.k() * (x - p.x0())));
        boolean hardMin = s.evidenceCount() >= p.minEvidence();
        return hardMin && sigmoid >= p.passThreshold();
    }

    private double normCount(int c){ return Math.min(1.0, c / 8.0); }
    private double clip01(double v){ return Math.max(0.0, Math.min(1.0, v)); }
}