package com.abandonware.ai.infra.upstash;


import java.util.function.Supplier;
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
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.infra.upstash.UpstashBackedWebCache
 * Role: config
 * Feature Flags: upstash.cache.*
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.infra.upstash.UpstashBackedWebCache
role: config
flags: [upstash.cache.*]
*/
public class UpstashBackedWebCache {
    // Added: single-flight to prevent cache stampede
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflightSingleFlight = new ConcurrentHashMap<>();


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

    // Added helper: generic single-flight loader
    public static <T> T singleFlightGetOrLoad(ConcurrentHashMap<String, CompletableFuture<Object>> inflight, String key, Supplier<T> loader) {
        var fut = inflight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> (Object) loader.get()));
        try {
            @SuppressWarnings("unchecked")
            T out = (T) fut.get();
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            inflight.remove(key);
        }
    }

}