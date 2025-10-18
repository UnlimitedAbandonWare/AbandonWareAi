package com.example.lms.service.embedding;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Lightweight embedding cache interface with a simple in‑memory reference implementation.
 * The default map‑backed cache is footprint‑aware enough for small/medium deployments and can
 * be replaced later with Caffeine/Redis without touching call‑sites.
 */
public interface EmbeddingCache {
    Optional<float[]> get(String key);
    void put(String key, float[] vec, Duration ttl);

    /**
     * Get the value or compute and cache it for the provided TTL.
     */
    default float[] getOrCompute(String key, Supplier<float[]> compute, Duration ttl) {
        Optional<float[]> v = get(key);
        if (v.isPresent()) return v.get();
        float[] val = compute.get();
        if (val != null) put(key, val, ttl);
        return val;
    }

    /** Deterministic cache key for a free‑form text. */
    static String keyFor(String text) {
        if (text == null) return "null";
        String norm = text.trim().toLowerCase();
        // A tiny, dependency‑free SHA‑256 (hex) for stable keys
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(norm.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // Fallback: plain hashCode (process‑local)
            return Integer.toHexString(norm.hashCode());
        }
    }

    /**
     * In‑JVM map‑backed cache with a naive TTL (best‑effort).
     * Not perfect under clock‑skew/long GC pauses, but good enough for speed‑ups.
     */
    final class InMemory implements EmbeddingCache {
        private static final class Entry {
            final float[] v;
            final long expireAt;
            Entry(float[] v, long expireAt) { this.v = v; this.expireAt = expireAt; }
        }
        private final Map<String, Entry> map = new ConcurrentHashMap<>();

        @Override public Optional<float[]> get(String key) {
            Entry e = map.get(key);
            if (e == null) return Optional.empty();
            if (e.expireAt > 0 && System.currentTimeMillis() > e.expireAt) {
                map.remove(key);
                return Optional.empty();
            }
            return Optional.of(e.v);
        }

        @Override public void put(String key, float[] vec, Duration ttl) {
            long exp = (ttl == null || ttl.isZero() || ttl.isNegative())
                    ? 0L : (System.currentTimeMillis() + ttl.toMillis());
            map.put(key, new Entry(vec, exp));
        }
    }
}
