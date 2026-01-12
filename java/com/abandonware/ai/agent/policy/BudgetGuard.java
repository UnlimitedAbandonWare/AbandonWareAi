package com.abandonware.ai.agent.policy;


/**
 * Simple budget guard with configurable limits. Uses a per-request estimated
 * cost (USD) and tokens to decide whether to allow an operation.
 * Defaults can be configured via system properties:
 *   -Dagent.budget.maxUsd=0.10
 *   -Dagent.budget.maxTokens=8000
 */
public class BudgetGuard {
    private final double maxUsd;
    private final long maxTokens;

    public BudgetGuard() {
        this.maxUsd = Double.parseDouble(System.getProperty("agent.budget.maxUsd", "0.10"));
        this.maxTokens = Long.parseLong(System.getProperty("agent.budget.maxTokens", "8000"));
    }

    public BudgetGuard(double maxUsd, long maxTokens) {
        this.maxUsd = maxUsd;
        this.maxTokens = maxTokens;
    }

    public boolean allow(String model, double estimatedCostUsd, long estimatedTokens) {
        return (estimatedCostUsd <= maxUsd) && (estimatedTokens <= maxTokens);
    }

    public double maxUsd() { return maxUsd; }
    public long maxTokens() { return maxTokens; }
}