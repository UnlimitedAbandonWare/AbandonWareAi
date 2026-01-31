package com.abandonware.ai.agent.integrations.service.rag.fusion;

import java.util.List;
public final class WeightedPowerMean {
    private final double p;
    public WeightedPowerMean(double p) { this.p = p; }
    public double combine(List<Double> scores, List<Double> weights) {
        if (scores == null || scores.isEmpty()) return 0.0;
        int n = scores.size();
        double wsum = 0.0;
        double acc = 0.0;
        for (int i=0;i<n;i++) {
            double w = (weights != null && i < weights.size()) ? weights.get(i) : 1.0;
            double s = scores.get(i) == null ? 0.0 : scores.get(i);
            wsum += w;
            acc += w * Math.pow(s, p);
        }
        if (wsum == 0.0) return 0.0;
        return Math.pow(acc / wsum, 1.0 / p);
    }
}