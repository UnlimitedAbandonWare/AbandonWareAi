package com.abandonware.ai.agent.policy;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.policy.BudgetGuard
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.policy.BudgetGuard
role: config
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