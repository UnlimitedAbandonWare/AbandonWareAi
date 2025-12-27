package com.example.lms.uaw.presence;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks in-flight user requests and the time the last user request finished.
 */
@Component
public class UserPresenceTracker {

    private final AtomicInteger inflight = new AtomicInteger(0);
    private final AtomicLong lastUserRequestFinishedAt = new AtomicLong(System.currentTimeMillis());

    public void onUserRequestStart() {
        inflight.incrementAndGet();
    }

    public void onUserRequestEnd() {
        lastUserRequestFinishedAt.set(System.currentTimeMillis());
        int v = inflight.decrementAndGet();
        if (v < 0) {
            inflight.set(0);
        }
    }

    public int inflight() {
        return Math.max(0, inflight.get());
    }

    public long lastUserRequestFinishedAt() {
        return lastUserRequestFinishedAt.get();
    }
}
