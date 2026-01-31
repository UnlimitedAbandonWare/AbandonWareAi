package com.abandonware.ai.trace;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.trace.TraceContext
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.trace.TraceContext
role: config
*/
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

// auto-patch: ExecutionBudget (lightweight)
public static final class ExecutionBudget {
  private long remainingMs;
  private int maxRerankK = 24;
  public ExecutionBudget(long remainingMs) { this.remainingMs = remainingMs; }
  public boolean exhausted() { return remainingMs <= 0; }
  public void consume(long ms) { remainingMs = Math.max(0, remainingMs - Math.max(0, ms)); }
  public int maxRerankK() { return maxRerankK; }
  public void setMaxRerankK(int k) { this.maxRerankK = k; }
}
private ExecutionBudget budget = new ExecutionBudget(3500);
public ExecutionBudget budget() { return budget; }