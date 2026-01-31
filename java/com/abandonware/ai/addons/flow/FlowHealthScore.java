package com.abandonware.ai.addons.flow;


public record FlowHealthScore(
        double pPlan, double pRetrieve, double pCriticize, double pSynthesize, double pDeliver,
        double safeScore
) {
    public boolean below(double threshold) { return safeScore < threshold; }
}