package com.example.lms.service.rag.runtime;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.runtime.TimeBudget
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.runtime.TimeBudget
role: config
*/
public class TimeBudget {
    private final long deadlineMs;
    public TimeBudget(long ms){ this.deadlineMs = System.currentTimeMillis() + Math.max(0, ms); }
    public long leftMs(){ return Math.max(0, deadlineMs - System.currentTimeMillis()); }
    public boolean expired(){ return leftMs() <= 0; }
    public void consume(long ms){ /* implicit by elapsed */ }
}