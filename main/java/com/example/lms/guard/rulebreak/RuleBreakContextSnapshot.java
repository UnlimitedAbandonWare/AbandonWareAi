package com.example.lms.guard.rulebreak;

import java.time.Instant;

/** Captures values without sharing the mutable context or extending its lifetime. */
public record RuleBreakContextSnapshot(boolean present, boolean active, RuleBreakPolicy policy,
        String tokenHash, Instant expiresAt, String requestId, String sessionId) {
    public static RuleBreakContextSnapshot capture() {
        RuleBreakContext ctx = RuleBreakContextHolder.get();
        return ctx == null ? new RuleBreakContextSnapshot(false, false, null, null, null, null, null)
                : new RuleBreakContextSnapshot(true, ctx.isActive(), ctx.getPolicy(), ctx.getTokenHash(),
                        ctx.getExpiresAt(), ctx.getRequestId(), ctx.getSessionId());
    }

    public Scope bind() {
        RuleBreakContext previous = RuleBreakContextHolder.get();
        if (!present) {
            RuleBreakContextHolder.clear();
        } else {
            RuleBreakContext ctx = new RuleBreakContext();
            ctx.setActive(active);
            ctx.setPolicy(policy);
            ctx.setTokenHash(tokenHash);
            ctx.setExpiresAt(expiresAt);
            ctx.setRequestId(requestId);
            ctx.setSessionId(sessionId);
            RuleBreakContextHolder.set(ctx);
        }
        return new Scope(previous);
    }

    @Override public String toString() { return "RuleBreakContextSnapshot[present=" + present + "]"; }

    public static final class Scope implements AutoCloseable {
        private final RuleBreakContext previous;
        private boolean closed;
        private Scope(RuleBreakContext previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) RuleBreakContextHolder.clear();
            else RuleBreakContextHolder.set(previous);
        }
    }
}
