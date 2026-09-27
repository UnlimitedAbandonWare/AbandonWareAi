package com.example.lms.service.routing.plan;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private static final Logger log = LoggerFactory.getLogger(RouterDecisionCache.class);

    private record CacheEntry(String sliceFingerprint, Object value) {
    }

    private final String tracePrefix;
    private final boolean l2Enabled;
    private final Cache<String, CacheEntry> l2Cache;

    @Value("${addons.budget.default-ms:1500}")
    private long fallbackWaitMillis = 1_500L;

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
        String flightRole = existing == null ? "leader" : "follower";

        if (existing == null) {
            long leaderLeaseMillis = remainingWaitMillis();
            fresh.orTimeout(leaderLeaseMillis, TimeUnit.MILLISECONDS)
                    .whenComplete((ignored, failure) -> {
                        if (isTimeoutFailure(failure)) {
                            inflight.remove(inflightKey, fresh);
                        }
                    });
            try {
                T computed = supplier.get();
                CacheEntry stored = new CacheEntry(fp, computed);
                if (fresh.complete(stored)) {
                    TraceStore.put(traceKey, stored);
                    if (l2Enabled) {
                        l2Cache.put(traceKey, stored);
                    }
                }
            } catch (Throwable t) {
                traceSuppressed("compute", traceKey, t);
                fresh.completeExceptionally(t);
                throw t;
            } finally {
                inflight.remove(inflightKey, fresh);
            }
        }

        CacheEntry resolved = awaitFlight(future, inflightKey, traceKey, flightRole);
        if (resolved != null && expectedType.isInstance(resolved.value)) {
            return expectedType.cast(resolved.value);
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

    private CacheEntry awaitFlight(
            CompletableFuture<CacheEntry> future,
            String inflightKey,
            String traceKey,
            String role) {
        long remainingMillis = remainingWaitMillis();
        long startedNanos = System.nanoTime();
        try {
            CacheEntry resolved = future.get(remainingMillis, TimeUnit.MILLISECONDS);
            traceWait(traceKey, role, startedNanos, remainingMillis,
                    "success", "completed", "not_required");
            return resolved;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            traceWait(traceKey, role, startedNanos, remainingMillis,
                    "cancelled", "wait_interrupted", "not_owner");
            CancellationException cancelled = new CancellationException("router_decision_wait_interrupted");
            cancelled.initCause(interrupted);
            throw cancelled;
        } catch (TimeoutException timeout) {
            traceWait(traceKey, role, startedNanos, remainingMillis,
                    "timeout", "follower_deadline_expired", "not_owner");
            throw new CompletionException("router_decision_wait_timeout", timeout);
        } catch (CancellationException cancelled) {
            boolean cleaned = inflight.remove(inflightKey, future);
            traceWait(traceKey, role, startedNanos, remainingMillis,
                    "cancelled", "shared_flight_cancelled", cleanupReason(cleaned));
            throw cancelled;
        } catch (ExecutionException completedExceptionally) {
            Throwable cause = completedExceptionally.getCause() == null
                    ? completedExceptionally
                    : completedExceptionally.getCause();
            boolean timedOut = isTimeoutFailure(cause);
            boolean cleaned = inflight.remove(inflightKey, future);
            traceWait(traceKey, role, startedNanos, remainingMillis,
                    timedOut ? "timeout" : "failed",
                    timedOut ? "leader_lease_expired" : "leader_failed",
                    cleanupReason(cleaned));
            traceSuppressed("wait", traceKey, cause);
            throw new CompletionException(
                    timedOut ? "router_decision_leader_timeout" : "router_decision_leader_failed",
                    cause);
        }
    }

    private long remainingWaitMillis() {
        TimeBudget budget = TimeBudgetContext.get();
        return budget == null
                ? Math.max(1L, fallbackWaitMillis)
                : Math.max(1L, budget.remainingMillis());
    }

    private void traceWait(
            String traceKey,
            String role,
            long startedNanos,
            long remainingMillis,
            String outcome,
            String reason,
            String cleanupResult) {
        try {
            TraceStore.put("router.plan.cache.wait.keyHash", SafeRedactor.hashValue(traceKey));
            TraceStore.put("router.plan.cache.wait.role", SafeRedactor.traceLabelOrFallback(role, "unknown"));
            TraceStore.put("router.plan.cache.wait.inflightCount", inflight.size());
            TraceStore.put("router.plan.cache.wait.elapsedMs",
                    Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
            TraceStore.put("router.plan.cache.wait.remainingMs", Math.max(0L, remainingMillis));
            TraceStore.put("router.plan.cache.wait.outcome", SafeRedactor.traceLabelOrFallback(outcome, "unknown"));
            TraceStore.put("router.plan.cache.wait.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            TraceStore.put("router.plan.cache.wait.cleanupResult",
                    SafeRedactor.traceLabelOrFallback(cleanupResult, "unknown"));
        } catch (RuntimeException traceFailure) {
            log.debug("[AWX][router][plan-cache] wait trace failed errorType={}", errorType(traceFailure));
        }
    }

    private static String cleanupReason(boolean cleaned) {
        return cleaned ? "removed" : "already_removed_or_replaced";
    }

    private static boolean isTimeoutFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            if (current.getCause() == null || current.getCause() == current) {
                return false;
            }
            current = current.getCause();
        }
        return false;
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

    private static void traceSuppressed(String stage, String traceKey, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        try {
            TraceStore.put("router.plan.cache.suppressed", true);
            TraceStore.put("router.plan.cache.suppressed.stage", safeStage);
            TraceStore.put("router.plan.cache.suppressed.errorType", errorType(failure));
            if (traceKey != null && !traceKey.isBlank()) {
                TraceStore.put("router.plan.cache.suppressed.keyHash", SafeRedactor.hashValue(traceKey));
                TraceStore.put("router.plan.cache.suppressed.keyLength", traceKey.length());
            }
        } catch (RuntimeException traceFailure) {
            log.debug("[AWX][router][plan-cache] suppression trace failed stage={} errorType={}",
                    safeStage, errorType(traceFailure));
        }
    }

    private static String errorType(Throwable failure) {
        return failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
