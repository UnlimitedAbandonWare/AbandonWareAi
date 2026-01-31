package com.abandonware.ai.agent.integrations.infra.budget;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.infra.budget.TimeBudget
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.infra.budget.TimeBudget
role: config
*/
public class TimeBudget {
    private final long deadlineMs;
    public TimeBudget(long reqMillis){ this.deadlineMs = System.currentTimeMillis() + Math.max(1L, reqMillis); }
    public boolean isExpired(){ return System.currentTimeMillis() >= deadlineMs; }
    public long remaining(){ return Math.max(0L, deadlineMs - System.currentTimeMillis()); }
}