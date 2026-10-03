package com.example.lms.service.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.example.lms.search.TraceStore;

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

    System.Logger LOG = System.getLogger(EmbeddingCache.class.getName());

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


    enum ReservationState { HIT, OWNER, JOIN, REJECTED }

    /** A claim on the existing per-key flight; closing a JOIN never cancels its owner. */
    interface Reservation extends AutoCloseable {
        ReservationState state();
        float[] await(long waitMillis);
        float[] complete(float[] value, Duration ttl, boolean cacheable);
        @Override void close();
    }

    Map<String, Reservation> reserveBatch(java.util.Collection<String> keys, long waitMillis);

    default float[] getOrCompute(String key, Supplier<float[]> computer, Duration ttl, long waitMillis) {
        return getOrCompute(key, computer, ttl);
    }

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
            LOG.log(System.Logger.Level.DEBUG, "[EmbeddingCache] fail-soft stage={0}", "keyFor.sha256");
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
            LOG.log(System.Logger.Level.DEBUG, "[EmbeddingCache] fail-soft stage={0}", "keyForV2.sha256");
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
     * Bounded in-memory cache with TTL and least-recently-used eviction.
     *
     * <p>
     * Admitted callers computing the same key share one flight. Both stored maps
     * are limited to 1024 entries by default. When every flight slot is occupied,
     * a new key returns its stale value or an empty vector without invoking the
     * supplier. Existing flights remain joinable and are never interrupted.
     * Non-positive or absent TTL does not retain newly computed values; an
     * existing fresh value keeps its original TTL. Supplier execution remains
     * synchronous and is not forcibly timed out by this cache.
     * </p>
     */
    final class InMemory implements EmbeddingCache {
        private static final int DEFAULT_MAX_ENTRIES = 1024;

        private static final class Entry {
            final float[] value;
            final long expireAtMillis;

            Entry(float[] value, long expireAtMillis) {
                this.value = value.clone();
                this.expireAtMillis = expireAtMillis;
            }
        }

        private static final class Flight {
            final CompletableFuture<float[]> result = new CompletableFuture<>();
            final float[] stale;

            Flight(Entry entry) {
                stale = entry == null ? new float[0] : entry.value;
            }
        }

        private final Object lock = new Object();
        private final Map<String, Entry> map = new LinkedHashMap<>(16, 0.75f, true);
        private final Map<String, Flight> inflight = new HashMap<>();
        private final Clock clock;
        private final int maxEntries;

        public InMemory() {
            this(Clock.systemUTC(), DEFAULT_MAX_ENTRIES);
        }

        InMemory(Clock clock, int maxEntries) {
            this.clock = Objects.requireNonNull(clock, "clock");
            if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
            this.maxEntries = maxEntries;
        }

        @Override
        public float[] getOrCompute(String key, Supplier<float[]> computer, Duration ttl) {
            return getOrCompute(key, computer, ttl, 30_000L);
        }

        @Override
        public float[] getOrCompute(String key, Supplier<float[]> computer, Duration ttl, long waitMillis) {
            if (key == null || key.isBlank()) {
                float[] value = compute(computer, true);
                return value == null ? new float[0] : value;
            }

            long startedAt = clock.millis();
            Flight flight;
            boolean leader;
            synchronized (lock) {
                Entry entry = map.get(key);
                if (entry != null && entry.expireAtMillis >= startedAt) {
                    TraceStore.inc("embeddingCache.hit.count");
                    traceSize();
                    return entry.value.clone();
                }
                TraceStore.inc("embeddingCache.miss.count");
                if (entry != null) {
                    map.remove(key);
                    TraceStore.inc("embeddingCache.expired.count");
                }
                if (boundedWaitMillis(waitMillis) <= 0) {
                    traceSize();
                    return entry == null ? new float[0] : entry.value.clone();
                }
                flight = inflight.get(key);
                leader = flight == null;
                if (leader) {
                    if (inflight.size() >= maxEntries) {
                        traceSize();
                        return entry == null ? new float[0] : entry.value.clone();
                    }
                    flight = new Flight(entry);
                    inflight.put(key, flight);
                }
                traceSize();
            }

            if (!leader) {
                try {
                    float[] v = flight.result.get(boundedWaitMillis(waitMillis), TimeUnit.MILLISECONDS);
                    return (v == null) ? new float[0] : v.clone();
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    LOG.log(System.Logger.Level.DEBUG, "[EmbeddingCache] fail-soft stage={0}", "singleFlight.wait");
                    return flight.stale.clone();
                }
            }

            float[] ret = flight.stale;
            try {
                float[] computed = compute(computer, false);
                if (computed != null && computed.length > 0) {
                    ret = computed;
                    if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                        long until = expiry(startedAt, ttl);
                        synchronized (lock) {
                            long now = clock.millis();
                            // An invalidated owner must not resurrect a value or replace a new flight.
                            if (inflight.get(key) == flight && until >= now) {
                                removeExpired(now);
                                if (map.size() >= maxEntries) {
                                    map.remove(map.keySet().iterator().next());
                                    TraceStore.inc("embeddingCache.evicted.count");
                                }
                                map.put(key, new Entry(computed, until));
                            }
                        }
                    }
                }
                return ret;
            } finally {
                synchronized (lock) {
                    inflight.remove(key, flight);
                    try {
                        flight.result.complete(ret.clone());
                    } catch (Exception ignored) {
                        LOG.log(System.Logger.Level.DEBUG, "[EmbeddingCache] fail-soft stage={0}", "singleFlight.complete");
                    }
                    traceSize();
                }
            }
        }


        /** Reserve all keys under one cache lock; no provider work runs here. */
        @Override
        public Map<String, Reservation> reserveBatch(java.util.Collection<String> keys, long waitMillis) {
            Map<String, Reservation> claims = new LinkedHashMap<>();
            synchronized (lock) {
                long now = clock.millis();
                for (String key : keys) {
                    if (claims.containsKey(key)) continue;
                    Entry entry = map.get(key);
                    ReservationState state;
                    Flight flight = null;
                    float[] ready = entry == null ? new float[0] : entry.value;
                    if (entry != null && entry.expireAtMillis >= now) {
                        state = ReservationState.HIT;
                        TraceStore.inc("embeddingCache.hit.count");
                    } else {
                        TraceStore.inc("embeddingCache.miss.count");
                        if (entry != null) {
                            map.remove(key);
                            TraceStore.inc("embeddingCache.expired.count");
                        }
                        if (key == null || key.isBlank() || boundedWaitMillis(waitMillis) <= 0) {
                            state = ReservationState.REJECTED;
                        } else if ((flight = inflight.get(key)) != null) {
                            state = ReservationState.JOIN;
                        } else if (inflight.size() >= maxEntries) {
                            state = ReservationState.REJECTED;
                        } else {
                            flight = new Flight(entry);
                            inflight.put(key, flight);
                            state = ReservationState.OWNER;
                        }
                    }
                    claims.put(key, new Claim(key, state, flight, ready, now));
                    TraceStore.inc("embeddingCache.reservation." + state.name().toLowerCase(java.util.Locale.ROOT));
                }
                traceSize();
            }
            return claims;
        }

        private final class Claim implements Reservation {
            private final String key;
            private final ReservationState state;
            private final Flight flight;
            private final float[] ready;
            private final long startedAt;
            private boolean settled;
            Claim(String key, ReservationState state, Flight flight, float[] ready, long startedAt) {
                this.key = key; this.state = state; this.flight = flight;
                this.ready = ready; this.startedAt = startedAt;
            }
            @Override public ReservationState state() { return state; }
            @Override public float[] await(long waitMillis) {
                if (flight == null) return ready.clone();
                try {
                    float[] value = flight.result.get(boundedWaitMillis(waitMillis), TimeUnit.MILLISECONDS);
                    return value == null ? new float[0] : value.clone();
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    LOG.log(System.Logger.Level.DEBUG, "[EmbeddingCache] fail-soft stage={0}", "singleFlight.wait");
                    return flight.stale.clone();
                }
            }
            @Override public float[] complete(float[] value, Duration ttl, boolean cacheable) {
                if (state != ReservationState.OWNER) throw new IllegalStateException("cache_claim_not_owner");
                float[] result = value == null || value.length == 0 ? flight.stale : value;
                synchronized (lock) {
                    if (settled) return result;
                    settled = true;
                    if (inflight.get(key) == flight) {
                        if (cacheable && value != null && value.length > 0
                                && ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                            long now = clock.millis(), until = expiry(startedAt, ttl);
                            if (until >= now) {
                                removeExpired(now);
                                if (map.size() >= maxEntries) {
                                    map.remove(map.keySet().iterator().next());
                                    TraceStore.inc("embeddingCache.evicted.count");
                                }
                                map.put(key, new Entry(value, until));
                            }
                        }
                        inflight.remove(key, flight);
                    }
                    // A backup-model result belongs only to its computing caller, not this fingerprint's joiners.
                    flight.result.complete((cacheable ? result : flight.stale).clone());
                    traceSize();
                }
                return result;
            }
            @Override public void close() {
                if (state != ReservationState.OWNER) return;
                synchronized (lock) {
                    if (settled) return;
                    settled = true;
                    inflight.remove(key, flight);
                    flight.result.complete(flight.stale.clone());
                    traceSize();
                }
            }
        }

        private static long boundedWaitMillis(long requested) {
            long limit = Math.min(30_000L, Math.max(0L, requested));
            var budget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
            return budget == null ? limit : Math.min(limit, budget.remainingMillis());
        }

        @Override
        public void invalidate(String key) {
            if (key == null || key.isBlank())
                return;
            synchronized (lock) {
                map.remove(key);
                Flight flight = inflight.remove(key);
                if (flight != null) {
                    try {
                        flight.result.complete(new float[0]);
                    } catch (Exception ignored) {
                        LOG.log(System.Logger.Level.DEBUG, "[EmbeddingCache] fail-soft stage={0}", "invalidate.complete");
                    }
                }
                traceSize();
            }
        }

        private static float[] compute(Supplier<float[]> computer, boolean blankKey) {
            try {
                return computer.get();
            } catch (Throwable failure) {
                TraceStore.inc("embeddingCache.computeFailed.count");
                if (blankKey) {
                    LOG.log(System.Logger.Level.DEBUG, "[EmbeddingCache] fail-soft stage={0}", "blankKey.compute");
                } else {
                    LOG.log(System.Logger.Level.DEBUG, "[EmbeddingCache] fail-soft stage={0}", "leader.compute");
                }
                return null;
            }
        }

        private static long expiry(long startedAt, Duration ttl) {
            try {
                return Math.addExact(startedAt, ttl.toMillis());
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }

        private void removeExpired(long now) {
            Iterator<Entry> entries = map.values().iterator();
            while (entries.hasNext()) {
                if (entries.next().expireAtMillis < now) {
                    entries.remove();
                    TraceStore.inc("embeddingCache.expired.count");
                }
            }
        }

        private void traceSize() {
            TraceStore.put("embeddingCache.size", map.size());
        }
    }
}
