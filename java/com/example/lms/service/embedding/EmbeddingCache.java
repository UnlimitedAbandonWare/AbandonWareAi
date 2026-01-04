package com.example.lms.service.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Lightweight cache contract for embedding vectors.
 *
 * <p>Provides a tiny in-memory implementation with TTL suitable for unit/integration tests
 * and as a safe default when a distributed cache is unavailable.</p>
 */
public interface EmbeddingCache {

    /**
     * Lookup an embedding vector by key, computing and storing it on miss.
     *
     * <p>The implementation SHOULD store the value with the given TTL but may ignore TTL if not supported.</p>
     *
     * <p><b>IMPORTANT:</b> implementations should avoid caching empty vectors produced by a failing embedder.
     * Otherwise the system can get "stuck" with empty embeddings even after the embedder recovers.</p>
     */
    float[] getOrCompute(String key, Supplier<float[]> computer, Duration ttl);

    /**
     * Generate a stable cache key for raw text. This normalizes whitespace and hashes the string (SHA-256).
     */
    static String keyFor(String text) {
        if (text == null || text.isBlank()) return "nil";
        String norm = text.trim().replaceAll("\\s+", " ");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("emb:");
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fallback to raw normalized text (bounded length)
            return "emb:" + Integer.toHexString(Objects.hash(norm));
        }
    }

    /**
     * Minimal in-memory implementation backed by a ConcurrentHashMap with TTL support.
     */
    final class InMemory implements EmbeddingCache {
        private static final class Entry {
            final float[] value;
            final long expireAtMillis; // -1 means no expiry

            Entry(float[] value, long expireAtMillis) {
                this.value = value;
                this.expireAtMillis = expireAtMillis;
            }
        }

        private final ConcurrentMap<String, Entry> map = new ConcurrentHashMap<>();

        @Override
        public float[] getOrCompute(String key, Supplier<float[]> computer, Duration ttl) {
            long now = System.currentTimeMillis();
            Entry e = map.get(key);
            if (e != null && (e.expireAtMillis < 0 || e.expireAtMillis >= now)) {
                return e.value;
            }

            float[] computed;
            try {
                computed = computer.get();
            } catch (Throwable t) {
                // Return stale value if present; otherwise an empty vector to keep callers resilient
                if (e != null) return e.value;
                return new float[0];
            }

            // IMPORTANT: do not cache empty embeddings.
            // When the embedding server is down, caching empty vectors would "poison" the cache
            // and delay recovery even after the server restarts.
            if (computed == null || computed.length == 0) {
                if (e != null && e.value != null && e.value.length > 0) {
                    return e.value; // stale-but-nonempty beats empty
                }
                return new float[0];
            }

            long until = (ttl == null || ttl.isZero() || ttl.isNegative()) ? -1 : now + ttl.toMillis();
            map.put(key, new Entry(computed, until));
            return computed;
        }
    }
}
