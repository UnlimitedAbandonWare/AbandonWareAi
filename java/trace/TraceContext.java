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
}