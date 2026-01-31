package trace;

/**
 * Minimal request-scope context (thread-local) for tracing flags.
 */
public class TraceContext {
  private static final ThreadLocal<Boolean> brave = ThreadLocal.withInitial(() -> false);
  private static final ThreadLocal<String> ruleBreak = ThreadLocal.withInitial(() -> null);
  private static final ThreadLocal<java.util.Map<String, Object>> staticFlags = ThreadLocal
      .withInitial(java.util.HashMap::new);

  public static void setBrave(boolean v) {
    brave.set(v);
  }

  public static boolean isBrave() {
    return brave.get();
  }

  public static void setRuleBreakToken(String t) {
    ruleBreak.set(t);
  }

  public static String getRuleBreakToken() {
    return ruleBreak.get();
  }

  /**
   * Static getter for thread-local flags.
   * Used by NaverSearchService and other callers for request tracing.
   */
  public static String get(String key) {
    Object val = staticFlags.get().get(key);
    return val == null ? null : String.valueOf(val);
  }

  /**
   * Static setter for thread-local flags.
   */
  public static void set(String key, Object val) {
    staticFlags.get().put(key, val);
  }

  /**
   * Clear all static flags for the current thread.
   */
  public static void clear() {
    staticFlags.get().clear();
  }

  private long deadlineNanos = -1L;
  private final java.util.Map<String, Object> flags = new java.util.HashMap<>();

  public TraceContext startWithBudget(java.time.Duration budget) {
    if (budget != null && !budget.isZero() && !budget.isNegative())
      this.deadlineNanos = System.nanoTime() + budget.toNanos();
    return this;
  }

  public long remainingMillis() {
    if (deadlineNanos <= 0)
      return Long.MAX_VALUE;
    return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000);
  }

  public void setFlag(String key, Object val) {
    flags.put(key, val);
  }

  public Object getFlag(String key) {
    return flags.get(key);
  }
}