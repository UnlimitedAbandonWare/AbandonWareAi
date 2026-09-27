package com.abandonware.ai.addons.budget;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class TimeBudget {
    private final LongSupplier nanoClock;
    private final long startNano;
    private final long durationNanos;
    /**
     * Cooperative cancellation: the object is shared by reference across the
     * request's async boundary (ContextPropagation), so cancelling here makes
     * every downstream remainingMillis()/expired() check report exhaustion and
     * stop starting new work. It does not interrupt threads or abort an
     * in-flight remote call.
     */
    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public TimeBudget(long budgetMillis) {
        this(budgetMillis, System::nanoTime);
    }

    TimeBudget(long budgetMillis, LongSupplier nanoClock) {
        this(nanoClock, nanoClock.getAsLong(),
                TimeUnit.MILLISECONDS.toNanos(Math.max(1L, budgetMillis)));
    }

    private TimeBudget(LongSupplier nanoClock, long startNano, long durationNanos) {
        this.nanoClock = nanoClock;
        this.startNano = startNano;
        this.durationNanos = durationNanos;
    }

    /** Adapts an existing JVM monotonic deadline without granting a new budget. */
    public static TimeBudget untilNanoDeadline(long deadlineNanos) {
        return untilNanoDeadline(deadlineNanos, System::nanoTime);
    }

    static TimeBudget untilNanoDeadline(long deadlineNanos, LongSupplier nanoClock) {
        long now = nanoClock.getAsLong();
        return new TimeBudget(nanoClock, now, Math.max(0L, deadlineNanos - now));
    }

    public long remainingMillis() {
        if (cancelled.get()) {
            return 0L;
        }
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, durationNanos - elapsedNanos()));
    }

    /** Mark this request's budget cancelled; downstream budget checks then stop new work. */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    /** Zero means do not wait; callers must not pass it as an unlimited I/O timeout. */
    public long capWaitMillis(long requestedMillis) {
        return Math.min(Math.max(0L, requestedMillis), remainingMillis());
    }

    private long elapsedNanos() {
        // nanoTime's origin may be negative and may wrap; only differences are meaningful.
        return Math.max(0L, nanoClock.getAsLong() - startNano);
    }
    public boolean expired() { return remainingMillis() <= 0; }
    public long elapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(elapsedNanos()); }
}
