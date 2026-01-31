package com.example.lms.service.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lightweight cache contract for embedding vectors.
 *
 * <p>
 * Provides a tiny in-memory implementation with TTL suitable for
 * unit/integration tests
 * and as a safe default when a distributed cache is unavailable.
 * </p>
 */
public interface EmbeddingCache {

    /**
     * Lookup an embedding vector by key, computing and storing it on miss.
     *
     * <p>
     * The implementation SHOULD store the value with the given TTL but may ignore
     * TTL if not supported.
     * </p>
     *
     * <p>
     * <b>IMPORTANT:</b> implementations should avoid caching empty vectors produced
     * by a failing embedder.
     * Otherwise the system can get "stuck" with empty embeddings even after the
     * embedder recovers.
     * </p>
     */
    float[] getOrCompute(String key, Supplier<float[]> computer, Duration ttl);

    /**
     * Best-effort invalidation.
     *
     * <p>
     * Used to avoid caching fallback embeddings (mixed model/dimension) and to
     * support
     * explicit cache busting after embedder failures/recoveries.
     * </p>
     */
    default void invalidate(String key) {
        // optional
    }

    /**
     * Generate a stable cache key for raw text. This normalizes whitespace and
     * hashes the string (SHA-256).
     */
    static String keyFor(String text) {
        if (text == null || text.isBlank())
            return "nil";
        String norm = text.trim().replaceAll("\s+", " ");
        try {
            return "emb:" + sha256Hex(norm);
        } catch (Exception e) {
            // Fallback to raw normalized text (bounded length)
            return "emb:" + Integer.toHexString(Objects.hash(norm));
        }
    }

    /**
     * Versioned cache key that incorporates embedder identity to prevent
     * cross-model/dimension collisions.
     *
     * <p>
     * This returns a short, safe key:
     * 
     * <pre>{@code embv2:<sha256>}</pre>
     *
     * <p>
     * The SHA-256 is computed over a composite of:
     * provider/model/dimensions/domain/docId/textHash.
     * </p>
     */
    static String keyForV2(String provider, String model, int dimensions, String domain, String docId, String text) {
        String p = (provider == null || provider.isBlank()) ? "unknown" : provider.trim();
        String m0 = (model == null || model.isBlank()) ? "unknown" : model.trim();
        String d0 = (domain == null || domain.isBlank()) ? "na" : domain.trim();
        String doc0 = (docId == null || docId.isBlank()) ? "na" : docId.trim();

        // Keep components reasonably stable. (Composite will be hashed anyway.)
        p = p.replaceAll("[^a-zA-Z0-9._:-]", "_");
        m0 = m0.replaceAll("[^a-zA-Z0-9._:-]", "_");
        d0 = d0.replaceAll("[^a-zA-Z0-9._:-]", "_");
        doc0 = doc0.replaceAll("[^a-zA-Z0-9._:-]", "_");

        int dim = Math.max(0, dimensions);

        String baseHash = keyFor(text); // emb:<sha256>
        String composite = p + "|" + m0 + "|" + dim + "|" + d0 + "|" + doc0 + "|" + baseHash;

        try {
            return "embv2:" + sha256Hex(composite);
        } catch (Exception e) {
            // 폴백: composite 해시 생성 실패 시 baseHash 기반으로 대체
            return "embv2:" + Integer.toHexString(Objects.hash(composite));
        }
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Minimal in-memory implementation backed by a ConcurrentHashMap with TTL
     * support.
     *
     * <p>
     * Includes a small single-flight guard so concurrent callers computing the same
     * key
     * do not stampede the embedder.
     * </p>
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
        private final ConcurrentMap<String, CompletableFuture<float[]>> inflight = new ConcurrentHashMap<>();

        @Override
        public float[] getOrCompute(String key, Supplier<float[]> computer, Duration ttl) {
            if (key == null || key.isBlank()) {
                try {
                    float[] v = computer.get();
                    return (v == null) ? new float[0] : v;
                } catch (Throwable t) {
                    return new float[0];
                }
            }

            long now = System.currentTimeMillis();
            Entry e = map.get(key);
            if (e != null && (e.expireAtMillis < 0 || e.expireAtMillis >= now)) {
                return e.value;
            }

            // single-flight: only one thread computes a given key at a time.
            CompletableFuture<float[]> created = new CompletableFuture<>();
            CompletableFuture<float[]> fut = inflight.putIfAbsent(key, created);
            boolean leader = false;
            if (fut == null) {
                fut = created;
                leader = true;
            }

            if (!leader) {
                try {
                    float[] v = fut.get(30, TimeUnit.SECONDS);
                    return (v == null) ? new float[0] : v;
                } catch (Exception ex) {
                    // Fall back to stale value if present; otherwise empty vector.
                    if (e != null && e.value != null && e.value.length > 0)
                        return e.value;
                    return new float[0];
                }
            }

            float[] computed;
            float[] ret;
            try {
                computed = computer.get();
            } catch (Throwable t) {
                // Return stale value if present; otherwise an empty vector to keep callers
                // resilient
                computed = null;
            }

            // IMPORTANT: do not cache empty embeddings.
            // When the embedding server is down, caching empty vectors would "poison" the
            // cache
            // and delay recovery even after the server restarts.
            if (computed == null || computed.length == 0) {
                if (e != null && e.value != null && e.value.length > 0) {
                    ret = e.value; // stale-but-nonempty beats empty
                } else {
                    ret = new float[0];
                }
            } else {
                long until = (ttl == null || ttl.isZero() || ttl.isNegative()) ? -1 : now + ttl.toMillis();
                map.put(key, new Entry(computed, until));
                ret = computed;
            }

            try {
                fut.complete(ret);
            } catch (Exception ignore) {
            } finally {
                inflight.remove(key, fut);
            }
            return ret;
        }

        @Override
        public void invalidate(String key) {
            if (key == null || key.isBlank())
                return;
            map.remove(key);
            CompletableFuture<float[]> f = inflight.remove(key);
            if (f != null && !f.isDone()) {
                try {
                    f.complete(new float[0]);
                } catch (Exception ignore) {
                }
            }
        }
    }
}
