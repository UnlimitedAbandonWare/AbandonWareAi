package com.abandonware.ai.trace;

public class TraceContext {
    private final String requestId;
    private final String sessionId;
    private long timeBudgetMs = 3500;
    private volatile boolean cancelled = false;

    public TraceContext(String requestId, String sessionId) {
        this.requestId = requestId;
        this.sessionId = sessionId;
    }

    // Extended constructor (optional)
    public TraceContext(String requestId, String sessionId, long timeBudgetMs) {
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.timeBudgetMs = timeBudgetMs;
    }

    public String getRequestId(){ return requestId; }
    public String getSessionId(){ return sessionId; }

    public long getTimeBudgetMs() { return timeBudgetMs; }
    public void setTimeBudgetMs(long ms) { this.timeBudgetMs = ms; }

    public boolean isCancelled() { return cancelled; }
    public void cancel() { this.cancelled = true; }
}
