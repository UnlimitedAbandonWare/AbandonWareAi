package com.abandonware.ai.infra.upstash;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Minimal 2-tier cache with single-flight to mitigate stampede. */
@Component
public class UpstashBackedWebCache {

    private static class Entry {
        final String value;
        final long expireAtEpochSec;
        Entry(String value, long expireAtEpochSec) {
            this.value = value; this.expireAtEpochSec = expireAtEpochSec;
        }
    }

    @Value("${upstash.cache.ttl-seconds:300}")
    private long ttlSeconds;

    @Value("${upstash.singleflight.enabled:true}")
    private boolean singleFlight;

    @Value("${upstash.singleflight.wait-timeout-ms:800}")
    private long waitTimeoutMs;

    private final Map<String, Entry> local = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    public void put(String key, String value) {
        long exp = Instant.now().getEpochSecond() + Math.max(1L, ttlSeconds);
        local.put(key, new Entry(value, exp));
    }

    public String get(String key) {
        Entry e = local.get(key);
        if (e == null) return null;
        if (Instant.now().getEpochSecond() > e.expireAtEpochSec) {
            local.remove(key);
            return null;
        }
        return e.value;
    }

    /** Returns cached value if present/valid; otherwise loads via loader. */
    public String getOrLoad(String key, Callable<String> loader) {
        String v = get(key);
        if (v != null) return v;

        if (!singleFlight) {
            try {
                v = loader.call();
            } catch (Exception ex) {
                return null;
            }
            if (v != null) put(key, v);
            return v;
        }

        CompletableFuture<String> fut = inflight.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    String nv;
                    try { nv = loader.call(); } catch (Exception ex) { return null; }
                    if (nv != null) put(k, nv);
                    return nv;
                } finally {
                    inflight.remove(k);
                }
            })
        );

        try {
            return fut.get(waitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            inflight.remove(key);
            try {
                v = loader.call();
                if (v != null) put(key, v);
                return v;
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
