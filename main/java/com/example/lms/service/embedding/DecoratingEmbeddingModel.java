package com.example.lms.service.embedding;

import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.trace.TraceLogger;
import com.example.lms.vector.EmbeddingFingerprint;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * EmbeddingModel decorator that adds per-text caching.
 *
 * <p>Patch notes:
 * <ul>
 *   <li>Uses a v2 cache key (provider/model/dim + domain/docId + text hash) when fingerprint is available,
 *   preventing cache poisoning across fallback chains (e.g., 1536 vs 4096 dimensions).</li>
 *   <li>Also caches {@link #embed(String)} (query embeddings) to unify the code-path and reduce repeated calls.</li>
 *   <li>When a fallback/backup embedder is used, it invalidates the cache key to avoid mixed-model contamination.</li>
 *   <li>Never caches empty vectors (EmbeddingCache enforces this).</li>
 * </ul>
 */
public final class DecoratingEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(DecoratingEmbeddingModel.class);
    private static final int PER_ITEM_FALLBACK_CONSECUTIVE_FAILURE_LIMIT = 3;

    private final EmbeddingModel delegate;
    private final EmbeddingCache cache;
    private final Duration ttl;
    private final EmbeddingFingerprint fingerprint; // optional

    public DecoratingEmbeddingModel(EmbeddingModel delegate, EmbeddingCache cache, Duration ttl) {
        this(delegate, cache, ttl, null);
    }

    public DecoratingEmbeddingModel(EmbeddingModel delegate, EmbeddingCache cache, Duration ttl, EmbeddingFingerprint fingerprint) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cache = Objects.requireNonNullElseGet(cache, EmbeddingCache.InMemory::new);
        this.ttl = (ttl == null) ? Duration.ofMinutes(15) : ttl;
        this.fingerprint = fingerprint;
    }

    @Override
    public Response<Embedding> embed(String text) {
        if (text == null) return Response.from(Embedding.from(new float[0]));
        String key = cacheKeyForQuery(text);
        float[] vec = getCachedVector(key, () -> delegate.embed(text), "query");
        return Response.from(Embedding.from(vec));
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        if (textSegment == null) return Response.from(Embedding.from(new float[0]));
        String key = cacheKeyFor(textSegment);
        float[] vec = getCachedVector(key, () -> delegate.embed(textSegment), "segment");
        return Response.from(Embedding.from(vec));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        try {
            TraceStore.put("embed.failover.used.cur", null);
            TraceStore.put("embed.failover.stage.cur", null);
            TraceStore.put("embed.batch.perItemFallback.stopped", false);
            TraceStore.put("embed.batch.perItemFallback.remaining", 0);
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.batchFailoverResetTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.batchFailoverResetTrace"); }
        if (textSegments == null || textSegments.isEmpty()) {
            return Response.from(List.of());
        }
        long batchDeadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(batchWaitMillis());
        String expectedIdentity = cacheIdentity();
        List<Embedding> out = new ArrayList<>(java.util.Collections.nCopies(textSegments.size(), null));
        java.util.Map<String, TextSegment> unique = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<Integer>> positions = new java.util.LinkedHashMap<>();
        for (int i = 0; i < textSegments.size(); i++) {
            TextSegment segment = textSegments.get(i);
            if (segment == null) { out.set(i, Embedding.from(new float[0])); continue; }
            String key = cacheKeyFor(segment);
            unique.putIfAbsent(key, segment);
            positions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
        }
        var claims = cache.reserveBatch(unique.keySet(), remainingBatchMillis(batchDeadlineNanos));
        List<TextSegment> misses = new ArrayList<>();
        List<String> missKeys = new ArrayList<>();
        List<EmbeddingCache.Reservation> owners = new ArrayList<>();
        boolean dbg = isDbgSearch();
        try {
            for (var entry : unique.entrySet()) {
                var claim = claims.get(entry.getKey());
                if (claim.state() == EmbeddingCache.ReservationState.OWNER) {
                    misses.add(entry.getValue()); missKeys.add(entry.getKey()); owners.add(claim);
                }
                if (dbg) {
                    inc(claim.state() == EmbeddingCache.ReservationState.HIT ? "embed.cache.hit" : "embed.cache.miss");
                    inc(claim.state() == EmbeddingCache.ReservationState.HIT ? "embed.batch.cache.hit" : "embed.batch.cache.miss");
                }
            }
            java.util.Map<String, Embedding> ownedResults = new java.util.HashMap<>();
            // Complete every owned key before joining another caller's flight.
            if (!misses.isEmpty() && remainingBatchMillis(batchDeadlineNanos) > 0) {
                List<Embedding> computed = batchEmbedMisses(misses, missKeys, owners, expectedIdentity, batchDeadlineNanos);
                for (int i = 0; i < missKeys.size(); i++) {
                    ownedResults.put(missKeys.get(i), computed.get(i));
                }
            }
            for (var owner : owners) owner.close();
            for (var entry : positions.entrySet()) {
                var claim = claims.get(entry.getKey());
                Embedding own = ownedResults.get(entry.getKey());
                float[] vector = own == null ? claim.await(remainingBatchMillis(batchDeadlineNanos)) : own.vector();
                for (int position : entry.getValue()) out.set(position, Embedding.from(vector.clone()));
            }
        } finally {
            // Includes provider errors, short fallback and interrupted owners; JOIN close is a no-op.
            for (var claim : claims.values()) claim.close();
        }
        if (dbg) {
            try {
                TraceStore.put("embed.batch.size.last", textSegments.size());
                TraceStore.put("embed.batch.unique.last", unique.size());
                TraceStore.put("embed.batch.miss.last", misses.size());
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.batchStatsTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.batchStatsTrace"); }
        }
        return Response.from(out);
    }

    private static long batchWaitMillis() {
        if (Thread.currentThread().isInterrupted()) return 0L;
        var budget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        return budget == null ? 30_000L : Math.min(30_000L, budget.remainingMillis());
    }

    private static long remainingBatchMillis(long deadlineNanos) {
        return Math.min(batchWaitMillis(), Math.max(0L,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
    }

    private boolean validVector(float[] vector, boolean failover) {
        if (vector == null || vector.length == 0) return false;
        if (!failover && fingerprint != null && fingerprint.dimensions() > 0
                && vector.length != fingerprint.dimensions()) return false;
        for (float value : vector) if (!Float.isFinite(value)) return false;
        return true;
    }

    private List<Embedding> batchEmbedMisses(List<TextSegment> misses, List<String> missKeys,
            List<EmbeddingCache.Reservation> owners, String expectedIdentity, long batchDeadlineNanos) {
        boolean dbg = isDbgSearch();

        Response<List<Embedding>> response = null;
        try {
            response = delegate.embedAll(misses);
        } catch (Throwable t) {
            EmbeddingTraceSuppressions.trace("decorator.batchEmbedMisses", t);
            log.debug("[Embedding] fail-soft stage={}", "decorator.batchEmbedMisses");
            if (dbg) {
                try {
                    TraceStore.put("embed.batch.error.cur",
                            String.format("errorHash=%s errorLength=%d",
                                    com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(t)),
                                    String.valueOf(t).length()));
                } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.batchErrorTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.batchErrorTrace"); }
            }
        }

        List<Embedding> batch = response == null ? null : response.content();
        if (batch == null || batch.size() != misses.size()) {
            log.warn("[EMBED_TRACE] batch embed miss path returned size={} expected={} -> per-item fallback",
                    batch == null ? -1 : batch.size(), misses.size());
            List<Embedding> fallback = new ArrayList<>(misses.size());
            int consecutiveUnrecoveredFailures = 0;
            for (int i = 0; i < misses.size(); i++) {
                if (remainingBatchMillis(batchDeadlineNanos) <= 0) {
                    while (fallback.size() < misses.size()) fallback.add(Embedding.from(new float[0]));
                    TraceStore.put("embed.batch.perItemFallback.stopped", true);
                    TraceStore.put("embed.batch.perItemFallback.remaining", misses.size() - i);
                    break;
                }
                TextSegment ts = misses.get(i);
                String key = missKeys.get(i);
                AtomicBoolean singleComputeUnavailable = new AtomicBoolean(false);
                float[] vec = getCachedVector(key, () -> {
                    try {
                        Response<Embedding> single = delegate.embed(ts);
                        Embedding content = single == null ? null : single.content();
                        float[] direct = content == null ? null : content.vector();
                        if (direct == null || direct.length == 0) {
                            singleComputeUnavailable.set(true);
                        }
                        return single;
                    } catch (RuntimeException | Error failure) {
                        singleComputeUnavailable.set(true);
                        throw failure;
                    }
                }, "segment", owners.get(i), expectedIdentity);
                fallback.add(Embedding.from(vec));
                if (singleComputeUnavailable.get() && vec.length == 0) {
                    consecutiveUnrecoveredFailures++;
                } else {
                    consecutiveUnrecoveredFailures = 0;
                }
                if (consecutiveUnrecoveredFailures >= PER_ITEM_FALLBACK_CONSECUTIVE_FAILURE_LIMIT) {
                    int remaining = misses.size() - i - 1;
                    for (int j = 0; j < remaining; j++) {
                        fallback.add(Embedding.from(new float[0]));
                    }
                    try {
                        TraceStore.put("embed.batch.perItemFallback.stopped", true);
                        TraceStore.put("embed.batch.perItemFallback.remaining", remaining);
                    } catch (Exception ignore) {
                        EmbeddingTraceSuppressions.trace("decorator.perItemFallbackStopTrace", ignore);
                        log.debug("[Embedding] fail-soft stage={}", "decorator.perItemFallbackStopTrace");
                    }
                    log.warn("[EMBED_TRACE] per-item fallback stopped after consecutive unrecovered failures attempted={} consecutiveFailures={} remaining={}",
                            i + 1, consecutiveUnrecoveredFailures, remaining);
                    break;
                }
            }
            return fallback;
        }

        boolean failoverUsed = false;
        String failoverStage = "";
        try {
            failoverUsed = truthy(TraceStore.get("embed.failover.used.cur"));
            Object st = TraceStore.get("embed.failover.stage.cur");
            if (st != null) failoverStage = String.valueOf(st);
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.batchFailoverReadTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.batchFailoverReadTrace"); }

        List<Embedding> out = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            Embedding e = batch.get(i);
            float[] vector = (e == null || e.vector() == null) ? new float[0] : e.vector();
            String key = missKeys.get(i);
            if (!validVector(vector, failoverUsed)) {
                vector = new float[0];
                TraceStore.inc("embed.batch.invalidVector.count");
            }
            boolean cacheable = !failoverUsed && Objects.equals(expectedIdentity, cacheIdentity());
            vector = owners.get(i).complete(vector, ttl, cacheable);
            out.add(Embedding.from(vector));
        }

        if (failoverUsed) {
            if (dbg) {
                try {
                    inc("embed.cache.invalidate.failover");
                    TraceStore.put("embed.failover.stage.last", failoverStage);
                    TraceStore.put("ml.embed.cache.invalidated", true);
                    TraceStore.put("ml.embed.failover.stage", failoverStage);
                    TraceStore.put("embed.batch.failover.used", true);
                } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.batchFailoverInvalidateTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.batchFailoverInvalidateTrace"); }
            }
            try {
                TraceLogger.emit("embed_cache_invalidate_failover", "embedding",
                        java.util.Map.of(
                                "key", "batch:" + misses.size(),
                                "stage", String.valueOf(failoverStage),
                                "kind", "segment_batch",
                                "computed", true
                        ));
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.batchFailoverTraceEvent", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.batchFailoverTraceEvent"); }
            log.debug("[EMBED_TRACE] batch fallback used; invalidated {} cache miss keys stage={}",
                    missKeys.size(), failoverStage);
        }
        return out;
    }

    private float[] getCachedVector(String key, Supplier<Response<Embedding>> compute, String kind) {
        return getCachedVector(key, compute, kind, null, null);
    }

    private float[] getCachedVector(String key, Supplier<Response<Embedding>> compute, String kind,
            EmbeddingCache.Reservation owner, String expectedIdentity) {
        boolean dbg = isDbgSearch();
        AtomicBoolean computed = new AtomicBoolean(false);
        AtomicBoolean failoverUsed = new AtomicBoolean(false);
        AtomicReference<String> failoverStage = new AtomicReference<>("");

        Supplier<float[]> loader = () -> {
            computed.set(true);

            // Clear per-call failover markers so a previous call can't "bleed" into this one.
            try {
                TraceStore.put("embed.failover.used.cur", null);
                TraceStore.put("embed.failover.stage.cur", null);
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.singleFailoverResetTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.singleFailoverResetTrace"); }

            Response<Embedding> r = null;
            try {
                r = compute.get();
            } catch (Throwable t) {
                // best-effort: allow cache layer to fall back to stale value
                EmbeddingTraceSuppressions.trace("decorator.singleCompute", t);
                log.debug("[Embedding] fail-soft stage={}", "decorator.singleCompute");
                if (dbg) {
                    try {
                        TraceStore.put("embed.error.cur",
                                String.format("errorHash=%s errorLength=%d",
                                        com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(t)),
                                        String.valueOf(t).length()));
                    } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.singleErrorTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.singleErrorTrace"); }
                }
                return new float[0];
            }

            float[] v = (r == null || r.content() == null) ? new float[0] : r.content().vector();

            // Detect fallback usage (set by OllamaEmbeddingModel when it calls the backup embedder).
            try {
                failoverUsed.set(truthy(TraceStore.get("embed.failover.used.cur")));
                Object st = TraceStore.get("embed.failover.stage.cur");
                if (st != null) failoverStage.set(String.valueOf(st));
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.singleFailoverReadTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.singleFailoverReadTrace"); }

            if (dbg) {
                try {
                    TraceStore.put("embed.kind.cur", kind);
                    TraceStore.put("embed.key.cur", shortKey(key));
                    TraceStore.put("embed.vec.len.cur", (v == null ? 0 : v.length));
                } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.singleStatsTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.singleStatsTrace"); }
            }
            if (owner != null && !validVector(v, failoverUsed.get())) return new float[0];
            return (v == null) ? new float[0] : v;
        };
        float[] vec;
        if (owner == null) {
            vec = cache.getOrCompute(key, loader, ttl, batchWaitMillis());
        } else {
            // Reserved fallback computes directly; never joins its own cache flight.
            float[] loaded = loader.get();
            vec = owner.complete(loaded, ttl,
                    !failoverUsed.get() && Objects.equals(expectedIdentity, cacheIdentity()));
        }

        if (dbg) {
            try {
                inc("embed.cache.calls");
                if (computed.get()) inc("embed.cache.miss");
                else inc("embed.cache.hit");
                TraceStore.put("embed.cache.last.kind", kind);
                TraceStore.put("embed.cache.last.key", shortKey(key));
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.cacheStatsTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.cacheStatsTrace"); }
        }

        // If this call used a fallback embedder, invalidate the cache entry to avoid mixing dimensions/models.
        if (computed.get() && failoverUsed.get()) {
            if (owner == null) cache.invalidate(key);
            if (dbg) {
                try {
                    inc("embed.cache.invalidate.failover");
                    TraceStore.put("embed.failover.stage.last", failoverStage.get());
                } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.singleFailoverLastTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.singleFailoverLastTrace"); }
            }
            // merge-boundary breadcrumbs (captured by TraceSnapshotStore)
            try {
                TraceStore.put("ml.embed.cache.invalidated", true);
                TraceStore.put("ml.embed.cache.key", shortKey(key));
                TraceStore.put("ml.embed.failover.stage", String.valueOf(failoverStage.get()));
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.singleFailoverSnapshotTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.singleFailoverSnapshotTrace"); }
            try {
                TraceLogger.emit("embed_cache_invalidate_failover", "embedding",
                        java.util.Map.of(
                                "key", shortKey(key),
                                "stage", String.valueOf(failoverStage.get()),
                                "kind", String.valueOf(kind),
                                "computed", computed.get()
                        ));
            } catch (Exception ignore) {
                EmbeddingTraceSuppressions.trace("decorator.singleFailoverTraceEvent", ignore);
                log.debug("[Embedding] fail-soft stage={}", "decorator.singleFailoverTraceEvent");
            }
            log.debug("[EMBED_TRACE] fallback used; invalidated key={} stage={}", shortKey(key), failoverStage.get());
        }

        return (vec == null) ? new float[0] : vec;
    }

    private String cacheKeyForQuery(String text) {
        // Prefer v2 key when we know the embedder identity.
        if (fingerprint != null) {
            return EmbeddingCache.keyForV2(
                    fingerprint.provider(),
                    cacheIdentity(),
                    fingerprint.dimensions(),
                    "query",
                    "q",
                    com.example.lms.trace.SafeRedactor.hashValue(text)
            );
        }
        return EmbeddingCache.keyFor(cacheIdentity() + "|" + com.example.lms.trace.SafeRedactor.hashValue(text));
    }

    private String cacheKeyFor(TextSegment ts) {
        if (ts == null) return "nil";
        String text = ts.text();

        // Prefer v2 key when we know the embedder identity.
        if (fingerprint != null) {
            String domain = "";
            String docId = "";
            try {
                if (ts.metadata() != null) {
                    var map = ts.metadata().toMap();
                    Object v1 = map.get(VectorMetaKeys.META_DOMAIN);
                    if (v1 != null) domain = String.valueOf(v1);
                    Object v2 = map.get(VectorMetaKeys.META_DOC_ID);
                    if (v2 != null) docId = String.valueOf(v2);
                }
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.cacheKeyMetadataTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.cacheKeyMetadataTrace"); }
            return EmbeddingCache.keyForV2(
                    fingerprint.provider(),
                    cacheIdentity(),
                    fingerprint.dimensions(),
                    domain,
                    docId,
                    com.example.lms.trace.SafeRedactor.hashValue(text)
            );
        }

        return EmbeddingCache.keyFor(cacheIdentity() + "|" + com.example.lms.trace.SafeRedactor.hashValue(text));
    }

    private String cacheIdentity() {
        String runtime = delegate instanceof OllamaEmbeddingModel ollama ? ollama.cacheIdentity() : "configured";
        return com.example.lms.trace.SafeRedactor.hashValue(
                (fingerprint == null ? "unknown" : fingerprint.fingerprint()) + "|" + runtime);
    }

    private static boolean isDbgSearch() {
        try {
            return truthy(TraceStore.get("dbg.search.enabled"));
        } catch (Exception ignore) {
            EmbeddingTraceSuppressions.trace("decorator.dbgSearchTrace", ignore);
            log.debug("[Embedding] fail-soft stage={}", "decorator.dbgSearchTrace");
            return false;
        }
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return false;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s);
    }

    private static void inc(String key) {
        try {
            Object v = TraceStore.get(key);
            long n = 0;
            if (v instanceof Number nn) n = nn.longValue();
            else if (v != null) {
                try {
                    n = Long.parseLong(String.valueOf(v).trim());
                } catch (NumberFormatException ignore) { EmbeddingTraceSuppressions.trace("decorator.incParseTrace", ignore); log.debug("[Embedding] fail-soft stage={} errorType={}", "decorator.incParseTrace", "invalid_number"); }
            }
            TraceStore.put(key, n + 1);
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("decorator.incTrace", ignore); log.debug("[Embedding] fail-soft stage={}", "decorator.incTrace"); }
    }

    private static String shortKey(String key) {
        if (key == null) return "";
        if (key.length() <= 28) return key;
        return key.substring(0, 28) + "...";
    }

}
