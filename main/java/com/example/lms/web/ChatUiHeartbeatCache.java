package com.example.lms.web;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class ChatUiHeartbeatCache {

    private final LongSupplier monotonicNanos;
    private final long ttlNanos;
    private final Object monitor = new Object();

    private volatile CachedPayload cachedPayload;

    ChatUiHeartbeatCache(LongSupplier monotonicNanos, Duration ttl) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.ttlNanos = Objects.requireNonNull(ttl, "ttl").toNanos();
        if (ttlNanos <= 0L) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    Map<String, Object> getOrRefresh(Supplier<Map<String, Object>> refresh) {
        Objects.requireNonNull(refresh, "refresh");
        long now = monotonicNanos.getAsLong();
        CachedPayload current = cachedPayload;
        if (isFresh(current, now)) {
            return responseAtAge(current, now);
        }

        synchronized (monitor) {
            now = monotonicNanos.getAsLong();
            current = cachedPayload;
            if (isFresh(current, now)) {
                return responseAtAge(current, now);
            }

            Map<String, Object> publicPayload = Map.copyOf(refresh.get());
            current = new CachedPayload(now, publicPayload);
            cachedPayload = current;
            return responseAtAge(current, now);
        }
    }

    private boolean isFresh(CachedPayload value, long now) {
        if (value == null) {
            return false;
        }
        long elapsed = now - value.capturedAtNanos();
        return elapsed >= 0L && elapsed < ttlNanos;
    }

    private Map<String, Object> responseAtAge(CachedPayload value, long now) {
        long elapsed = Math.max(0L, now - value.capturedAtNanos());
        long ageMs = TimeUnit.NANOSECONDS.toMillis(elapsed);
        return ChatUiHeartbeatPayload.withAge(value.publicPayload(), ageMs);
    }

    private record CachedPayload(long capturedAtNanos, Map<String, Object> publicPayload) {
    }
}
