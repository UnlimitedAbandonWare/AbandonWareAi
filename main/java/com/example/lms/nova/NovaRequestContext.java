package com.example.lms.nova;


public final class NovaRequestContext {
    private static final ThreadLocal<Boolean> BRAVE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<RuleBreakContext> RB = ThreadLocal.withInitial(RuleBreakContext::disabled);

    private NovaRequestContext() {}

    public static void setBrave(boolean on) { BRAVE.set(on); }
    public static boolean isBrave() { return Boolean.TRUE.equals(BRAVE.get()); }

    public static void setRuleBreak(RuleBreakContext ctx) { RB.set(ctx); }
    public static RuleBreakContext getRuleBreak() { return RB.get(); }
    public static boolean hasRuleBreak() { return getRuleBreak() != null && getRuleBreak().enabled(); }
    public static void clearRuleBreak() { RB.set(RuleBreakContext.disabled()); }
}