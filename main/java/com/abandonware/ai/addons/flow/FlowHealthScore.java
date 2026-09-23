package com.abandonware.ai.addons.flow;


public record FlowHealthScore(
        double pPlan, double pRetrieve, double pCriticize, double pSynthesize, double pDeliver,
        double safeScore
) {
    public FlowHealthScore {
        pPlan = probability(pPlan);
        pRetrieve = probability(pRetrieve);
        pCriticize = probability(pCriticize);
        pSynthesize = probability(pSynthesize);
        pDeliver = probability(pDeliver);
        safeScore = probability(safeScore);
    }

    public boolean below(double threshold) { return safeScore < threshold; }

    private static double probability(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
