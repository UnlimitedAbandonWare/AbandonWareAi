package trace;

/**
 * Minimal request-scope context (thread-local) for tracing flags.
 */
public class TraceContext {
    private static final ThreadLocal<Boolean> brave = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> ruleBreak = ThreadLocal.withInitial(() -> null);

    public static void setBrave(boolean v) { brave.set(v); }
    public static boolean isBrave() { return brave.get(); }

    public static void setRuleBreakToken(String t) { ruleBreak.set(t); }
    public static String getRuleBreakToken() { return ruleBreak.get(); }

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