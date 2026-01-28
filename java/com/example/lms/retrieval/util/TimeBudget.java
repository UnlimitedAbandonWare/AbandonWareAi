package com.example.lms.retrieval.util;


/**
 * Simple utility for tracking elapsed time against a fixed budget.  A new
 * instance records the creation timestamp and exposes methods to obtain the
 * elapsed duration, remaining budget and whether the budget has been
 * exhausted.  This class is intended to assist with enforcing per-call
 * timeouts in multi-stage retrieval pipelines.  It makes no attempt
 * to interrupt or cancel operations once the budget has been exceeded;
 * consumers must poll {@link #remaining()} or {@link #exhausted()} and
 * react accordingly.
 */
public class TimeBudget {
    private final long start = System.currentTimeMillis();
    private final long totalMs;

    /**
     * Construct a new TimeBudget with the given total allowance.
     *
     * @param totalMs total budget in milliseconds
     */
    public TimeBudget(long totalMs) {
        this.totalMs = totalMs;
    }

    /**
     * @return the elapsed time in milliseconds since this budget was created
     */
    public long elapsed() {
        return System.currentTimeMillis() - start;
    }

    /**
     * @return the remaining time in milliseconds before the budget is exhausted
     */
    public long remaining() {
        long rem = totalMs - elapsed();
        return Math.max(0L, rem);
    }

    /**
     * @return true if the elapsed time is greater than or equal to the total budget
     */
    public boolean exhausted() {
        return remaining() <= 0L;
    }
}