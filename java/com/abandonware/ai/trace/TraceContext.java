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

private long deadlineNanos = -1L;
private final java.util.Map<String,Object> flags = new java.util.HashMap<>();
public TraceContext startWithBudget(java.time.Duration budget) {
  if (budget != null && !budget.isZero() && !budget.isNegative())
    this.deadlineNanos = System.nanoTime() + budget.toNanos();
  return this;
}
public long remainingMillis() {
  if (deadlineNanos <= 0) return Long.MAX_VALUE;
  return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000);
}
public void setFlag(String key, Object val){ flags.put(key, val); }
public Object getFlag(String key){ return flags.get(key); }
}