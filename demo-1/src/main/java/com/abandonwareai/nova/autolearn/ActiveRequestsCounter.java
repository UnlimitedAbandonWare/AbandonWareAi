package com.abandonwareai.nova.autolearn;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks in-flight requests and the time the last request finished.
 */
@Component
public class ActiveRequestsCounter {
    private final AtomicInteger active = new AtomicInteger(0);
    private final AtomicLong lastRequestFinishedAt = new AtomicLong(System.currentTimeMillis());

    public void inc() {
        active.incrementAndGet();
    }

    public void dec() {
        lastRequestFinishedAt.set(System.currentTimeMillis());
        int v = active.decrementAndGet();
        if (v < 0) {
            active.set(0);
        }
    }

    public int get() {
        return Math.max(0, active.get());
    }

    public long getLastRequestFinishedAt() {
        return lastRequestFinishedAt.get();
    }
}
