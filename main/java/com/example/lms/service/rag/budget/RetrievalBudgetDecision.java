package com.example.lms.service.rag.budget;

public record RetrievalBudgetDecision(
        int webTopK,
        int vectorTopK,
        int kgTopK,
        int queryBurstCount,
        boolean enableHypernova,
        boolean enableMassiveExpansion,
        String reason
) {
    public RetrievalBudgetDecision {
        webTopK = Math.max(0, webTopK);
        vectorTopK = Math.max(0, vectorTopK);
        kgTopK = Math.max(0, kgTopK);
        queryBurstCount = Math.max(0, queryBurstCount);
        reason = reason == null ? "" : reason;
    }
}
