package com.example.lms.infra.time;

/**
 * Simple time budget holder.
 */
public class BudgetContext {
    private final long deadlineMs;

    public BudgetContext(long durationMs) {
        this.deadlineMs = System.currentTimeMillis() + Math.max(0L, durationMs);
    }

    public long deadlineMs() { return deadlineMs; }

    /** Remaining time (ms) clamped at 0 */
    public long remainingMs() {
        long r = deadlineMs - System.currentTimeMillis();
        return r <= 0 ? 0 : r;
    }
}