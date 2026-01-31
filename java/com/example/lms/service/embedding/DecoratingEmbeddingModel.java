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
        if (textSegments == null || textSegments.isEmpty()) {
            return Response.from(List.of());
        }
        List<Embedding> out = new ArrayList<>(textSegments.size());
        for (TextSegment ts : textSegments) {
            out.add(embed(ts).content());
        }
        return Response.from(out);
    }

    private float[] getCachedVector(String key, Supplier<Response<Embedding>> compute, String kind) {
        boolean dbg = isDbgSearch();
        AtomicBoolean computed = new AtomicBoolean(false);
        AtomicBoolean failoverUsed = new AtomicBoolean(false);
        AtomicReference<String> failoverStage = new AtomicReference<>("");

        float[] vec = cache.getOrCompute(key, () -> {
            computed.set(true);

            // Clear per-call failover markers so a previous call can't "bleed" into this one.
            try {
                TraceStore.put("embed.failover.used.cur", null);
                TraceStore.put("embed.failover.stage.cur", null);
            } catch (Exception ignore) {
            }

            Response<Embedding> r = null;
            try {
                r = compute.get();
            } catch (Throwable t) {
                // best-effort: allow cache layer to fall back to stale value
                if (dbg) {
                    try {
                        TraceStore.put("embed.error.cur", clip(t.toString(), 256));
                    } catch (Exception ignore) {
                    }
                }
                return new float[0];
            }

            float[] v = (r == null || r.content() == null) ? new float[0] : r.content().vector();

            // Detect fallback usage (set by OllamaEmbeddingModel when it calls the backup embedder).
            try {
                failoverUsed.set(truthy(TraceStore.get("embed.failover.used.cur")));
                Object st = TraceStore.get("embed.failover.stage.cur");
                if (st != null) failoverStage.set(String.valueOf(st));
            } catch (Exception ignore) {
            }

            if (dbg) {
                try {
                    TraceStore.put("embed.kind.cur", kind);
                    TraceStore.put("embed.key.cur", shortKey(key));
                    TraceStore.put("embed.vec.len.cur", (v == null ? 0 : v.length));
                } catch (Exception ignore) {
                }
            }
            return (v == null) ? new float[0] : v;
        }, ttl);

        if (dbg) {
            try {
                inc("embed.cache.calls");
                if (computed.get()) inc("embed.cache.miss");
                else inc("embed.cache.hit");
                TraceStore.put("embed.cache.last.kind", kind);
                TraceStore.put("embed.cache.last.key", shortKey(key));
            } catch (Exception ignore) {
            }
        }

        // If this call used a fallback embedder, invalidate the cache entry to avoid mixing dimensions/models.
        if (computed.get() && failoverUsed.get()) {
            cache.invalidate(key);
            if (dbg) {
                try {
                    inc("embed.cache.invalidate.failover");
                    TraceStore.put("embed.failover.stage.last", failoverStage.get());
                } catch (Exception ignore) {
                }
            }
            // merge-boundary breadcrumbs (captured by TraceSnapshotStore)
            try {
                TraceStore.put("ml.embed.cache.invalidated", true);
                TraceStore.put("ml.embed.cache.key", shortKey(key));
                TraceStore.put("ml.embed.failover.stage", String.valueOf(failoverStage.get()));
            } catch (Exception ignore) {
            }
            try {
                TraceLogger.emit("embed_cache_invalidate_failover", "embedding",
                        java.util.Map.of(
                                "key", shortKey(key),
                                "stage", String.valueOf(failoverStage.get()),
                                "kind", String.valueOf(kind),
                                "computed", computed.get()
                        ));
            } catch (Exception ignore) {
                // fail-soft
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
                    fingerprint.model(),
                    fingerprint.dimensions(),
                    "query",
                    "q",
                    text
            );
        }
        return EmbeddingCache.keyFor(text);
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
            } catch (Exception ignore) {
            }
            return EmbeddingCache.keyForV2(
                    fingerprint.provider(),
                    fingerprint.model(),
                    fingerprint.dimensions(),
                    domain,
                    docId,
                    text
            );
        }

        return EmbeddingCache.keyFor(text);
    }

    private static boolean isDbgSearch() {
        try {
            return truthy(TraceStore.get("dbg.search.enabled"));
        } catch (Exception ignore) {
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
                } catch (Exception ignore) {
                }
            }
            TraceStore.put(key, n + 1);
        } catch (Exception ignore) {
        }
    }

    private static String shortKey(String key) {
        if (key == null) return "";
        if (key.length() <= 28) return key;
        return key.substring(0, 28) + "...";
    }

    private static String clip(String s, int maxLen) {
        if (s == null) return "";
        if (maxLen <= 0) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }
}
