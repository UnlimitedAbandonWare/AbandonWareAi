package com.abandonware.ai.addons.budget;


public final class TimeBudget {
    private final long deadlineMillis;
    private final long startNano;

    public TimeBudget(long budgetMillis) {
        this.deadlineMillis = System.currentTimeMillis() + Math.max(1, budgetMillis);
        this.startNano = System.nanoTime();
    }

    public long remainingMillis() {
        return Math.max(0, deadlineMillis - System.currentTimeMillis());
    }
    public boolean expired() { return remainingMillis() <= 0; }
    public long elapsedMillis() { return (System.nanoTime() - startNano) / 1_000_000; }
}