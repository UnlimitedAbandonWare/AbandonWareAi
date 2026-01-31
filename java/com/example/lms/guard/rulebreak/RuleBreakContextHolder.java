package com.example.lms.guard.rulebreak;

/**
 * Thread-local holder for RuleBreakContext.
 *
 * <p>
 * 목적: HTTP 요청에서 파싱한 RuleBreakPolicy를 retrieval/plan 계층까지 전달.
 * </p>
 */
public final class RuleBreakContextHolder {

    private static final ThreadLocal<RuleBreakContext> CTX = new ThreadLocal<>();

    private RuleBreakContextHolder() {
    }

    public static RuleBreakContext get() {
        return CTX.get();
    }

    public static void set(RuleBreakContext ctx) {
        CTX.set(ctx);
    }

    public static void clear() {
        CTX.remove();
    }
}
