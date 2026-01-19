package com.example.lms.service.routing.plan;

import com.example.lms.search.TraceStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Cache for router/planner decisions.
 *
 * <p>
 * This is a two-level cache:
 * <ul>
 *   <li><b>L1</b>: request-scoped cache stored in {@link TraceStore} (survives async propagation)</li>
 *   <li><b>L2</b>: optional global Caffeine cache (TTL) to reduce drift when request context is cleared</li>
 * </ul>
 *
 * <p>
 * Each entry is slice-aware: if the slice fingerprint changes, the cached decision is invalidated.
 */
@Component
public class RouterDecisionCache {

    private record CacheEntry(String sliceFingerprint, Object value) {
    }

    private final String tracePrefix;
    private final boolean l2Enabled;
    private final Cache<String, CacheEntry> l2Cache;

    // De-duplicate in-flight computations for the same (key, slice).
    private final ConcurrentHashMap<String, CompletableFuture<CacheEntry>> inflight = new ConcurrentHashMap<>();

    public RouterDecisionCache(
            @Value("${routing.plan.cache.tracePrefix:router.plan.cache}") String tracePrefix,
            @Value("${routing.plan.cache.l2.enabled:false}") boolean l2Enabled,
            @Value("${routing.plan.cache.l2.maxSize:1024}") long maxSize,
            @Value("${routing.plan.cache.l2.expireSeconds:300}") long expireSeconds) {
        this.tracePrefix = (tracePrefix == null || tracePrefix.isBlank()) ? "router.plan.cache" : tracePrefix.trim();
        this.l2Enabled = l2Enabled;
        this.l2Cache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxSize))
                .expireAfterWrite(Math.max(1, expireSeconds), TimeUnit.SECONDS)
                .build();
    }

    /**
     * Returns a cached value iff the cached entry's slice fingerprint matches the provided slice.
     * Otherwise computes a new value, overwrites the cache entry, and returns the new value.
     */
    public <T> T getOrCompute(
            String namespace,
            String decisionKey,
            String sliceFingerprint,
            Class<T> expectedType,
            Supplier<T> supplier) {

        final String traceKey = traceKey(namespace, decisionKey);
        final String fp = (sliceFingerprint == null) ? "" : sliceFingerprint;

        // 1) L1 hit
        Object raw = TraceStore.get(traceKey);
        if (raw instanceof CacheEntry entry) {
            if (Objects.equals(entry.sliceFingerprint, fp) && expectedType.isInstance(entry.value)) {
                return expectedType.cast(entry.value);
            }
            // stale or wrong type → invalidate
            TraceStore.put(traceKey, null);
        }

        // 2) L2 hit
        if (l2Enabled) {
            CacheEntry l2 = l2Cache.getIfPresent(traceKey);
            if (l2 != null && Objects.equals(l2.sliceFingerprint, fp) && expectedType.isInstance(l2.value)) {
                TraceStore.put(traceKey, l2);
                return expectedType.cast(l2.value);
            }
        }

        // 3) Compute (with in-flight de-dup)
        final String inflightKey = traceKey + "|" + fp;
        CompletableFuture<CacheEntry> fresh = new CompletableFuture<>();
        CompletableFuture<CacheEntry> existing = inflight.putIfAbsent(inflightKey, fresh);
        CompletableFuture<CacheEntry> future = existing != null ? existing : fresh;

        if (existing == null) {
            try {
                T computed = supplier.get();
                CacheEntry stored = new CacheEntry(fp, computed);
                TraceStore.put(traceKey, stored);
                if (l2Enabled) {
                    l2Cache.put(traceKey, stored);
                }
                fresh.complete(stored);
            } catch (Throwable t) {
                fresh.completeExceptionally(t);
                throw t;
            } finally {
                inflight.remove(inflightKey);
            }
        }

        try {
            CacheEntry resolved = future.join();
            if (resolved != null && expectedType.isInstance(resolved.value)) {
                return expectedType.cast(resolved.value);
            }
        } catch (RuntimeException e) {
            // join() wraps checked exceptions; just rethrow
            throw e;
        }

        // Defensive fallback: compute synchronously
        T computed = supplier.get();
        CacheEntry stored = new CacheEntry(fp, computed);
        TraceStore.put(traceKey, stored);
        if (l2Enabled) {
            l2Cache.put(traceKey, stored);
        }
        return computed;
    }

    /** Best-effort read without computing (L1 → L2). */
    public <T> Optional<T> getIfPresent(String namespace, String decisionKey, String sliceFingerprint, Class<T> expectedType) {
        final String traceKey = traceKey(namespace, decisionKey);
        final String fp = (sliceFingerprint == null) ? "" : sliceFingerprint;

        Object raw = TraceStore.get(traceKey);
        if (raw instanceof CacheEntry entry) {
            if (Objects.equals(entry.sliceFingerprint, fp) && expectedType.isInstance(entry.value)) {
                return Optional.of(expectedType.cast(entry.value));
            }
        }
        if (l2Enabled) {
            CacheEntry l2 = l2Cache.getIfPresent(traceKey);
            if (l2 != null && Objects.equals(l2.sliceFingerprint, fp) && expectedType.isInstance(l2.value)) {
                TraceStore.put(traceKey, l2);
                return Optional.of(expectedType.cast(l2.value));
            }
        }
        return Optional.empty();
    }

    /** Force overwrite the cached value for a given (key, slice). */
    public void put(String namespace, String decisionKey, String sliceFingerprint, Object value) {
        final String traceKey = traceKey(namespace, decisionKey);
        final String fp = (sliceFingerprint == null) ? "" : sliceFingerprint;
        CacheEntry stored = new CacheEntry(fp, value);
        TraceStore.put(traceKey, stored);
        if (l2Enabled) {
            l2Cache.put(traceKey, stored);
        }
    }

    public void invalidate(String namespace, String decisionKey) {
        final String traceKey = traceKey(namespace, decisionKey);
        TraceStore.put(traceKey, null);
        if (l2Enabled) {
            l2Cache.invalidate(traceKey);
        }
    }

    private String traceKey(String namespace, String decisionKey) {
        String ns = (namespace == null ? "" : namespace.trim());
        String dk = (decisionKey == null ? "" : decisionKey.trim());
        return tracePrefix + ":" + ns + ":" + dk;
    }
}
