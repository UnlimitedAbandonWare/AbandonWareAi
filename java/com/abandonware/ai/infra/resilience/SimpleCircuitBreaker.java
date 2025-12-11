package com.abandonware.ai.infra.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Tiny, dependency-free circuit breaker with fail-soft behavior. */
public class SimpleCircuitBreaker {
    private final int failureThreshold;
    private final long halfOpenMillis;
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong lastFailureAt = new AtomicLong(0);

    public SimpleCircuitBreaker(int failureThreshold, long halfOpenMillis) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.halfOpenMillis = Math.max(1000, halfOpenMillis);
    }

    public boolean allow() {
        int f = failures.get();
        if (f < failureThreshold) return true;
        long since = System.currentTimeMillis() - lastFailureAt.get();
        return since >= halfOpenMillis;
    }

    public void recordSuccess() {
        failures.set(0);
    }

    public void recordFailure() {
        failures.incrementAndGet();
        lastFailureAt.set(System.currentTimeMillis());
    }
}