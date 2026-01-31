package com.abandonware.ai.agent.integrations.infra.budget;


/**
 * TimeBudget: simple wall-time stopwatch.
 */
public class TimeBudget {
    private final long deadlineMs;
    public TimeBudget(long reqMillis){ this.deadlineMs = System.currentTimeMillis() + Math.max(1L, reqMillis); }
    public boolean isExpired(){ return System.currentTimeMillis() >= deadlineMs; }
    public long remaining(){ return Math.max(0L, deadlineMs - System.currentTimeMillis()); }
}