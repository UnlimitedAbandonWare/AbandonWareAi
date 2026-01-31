
package com.example.lms.resilience;


public class BudgetContext {
    private final long deadlineEpochMs;

    public BudgetContext(long deadlineEpochMs) {
        this.deadlineEpochMs = deadlineEpochMs;
    }
    public long remainingMs() {
        return Math.max(0, deadlineEpochMs - System.currentTimeMillis());
    }
    public boolean expired() {
        return remainingMs() <= 0;
    }
}