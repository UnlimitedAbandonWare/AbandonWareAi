// src/main/java/service/rag/budget/TimeBudget.java
package service.rag.budget;

public final class TimeBudget {
    private final long startNanos;
    private final long deadlineNanos; // nanoTime 기준

    TimeBudget(long startNanos, long deadlineNanos) {
        this.startNanos = startNanos;
        this.deadlineNanos = deadlineNanos;
    }
    public long remainingMillis() {
        long remNanos = deadlineNanos - System.nanoTime();
        return remNanos <= 0 ? 0 : remNanos / 1_000_000L;
    }
    public boolean expired() { return remainingMillis() <= 0; }
    public long startNanos() { return startNanos; }
    public long deadlineNanos() { return deadlineNanos; }
}