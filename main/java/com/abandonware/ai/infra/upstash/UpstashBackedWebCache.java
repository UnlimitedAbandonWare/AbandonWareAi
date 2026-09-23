package com.abandonware.ai.infra.upstash;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Minimal 2-tier cache facade with a single-flight guard.
 * Only the in-JVM single-flight is implemented here to keep the surface area tiny.
 */
@Component
public class UpstashBackedWebCache {

    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    @Value("${upstash.cache.wait-timeout-ms:3000}")
    private long waitTimeoutMs;

    /**
     * Get or compute a value identified by {@code key}. The loader is executed at most once
     * concurrently for the same key (single-flight).  This method is synchronous by design.
     */
    public String getOrLoad(String key, Callable<String> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");

        final CompletableFuture<String> f = inflight.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        try {
                            return loader.call();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    } finally {
                        inflight.remove(k);
                    }
                }));
        try {
            return f.get(waitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            inflight.remove(key);
            throw new RuntimeException(e);
        }
    }
}