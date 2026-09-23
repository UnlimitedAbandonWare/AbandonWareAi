package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.mp.LowRankWhiteningStats;
import com.example.lms.trace.SafeRedactor;

import java.util.Locale;



/**
 * Applies a low-rank whitening transformation to query embedding vectors.
 *
 * <p>This adapter delegates to an underlying {@link LowRankWhiteningStats}
 * to perform the actual transform.  It is intentionally immutable and
 * lightweight; dependency injection of the stats object determines the
 * operational state.</p>
 */
public final class LowRankWhiteningTransform {
    private static final String UNKNOWN_PROVIDER = "unknown";

    private final LowRankWhiteningStats stats;
    private final String provider;

    /**
     * Constructs a new transform wrapper.
     *
     * @param stats the whitening statistics provider
     */
    public LowRankWhiteningTransform(LowRankWhiteningStats stats) {
        this(stats, UNKNOWN_PROVIDER);
    }

    public LowRankWhiteningTransform(LowRankWhiteningStats stats, String provider) {
        this.stats = stats;
        this.provider = normalizeProvider(provider);
    }

    public float[] apply(float[] vec) {
        if (vec == null) {
            TraceStore.put("hypernova.whitening.inputDim", 0);
            traceSkipped("null_input");
            return new float[0];
        }
        TraceStore.put("hypernova.whitening.inputDim", vec.length);
        String runtimeProvider = runtimeProviderOrConfigured();
        TraceStore.put("hypernova.whitening.provider", provider);
        TraceStore.put("hypernova.whitening.runtimeProvider", runtimeProvider);
        TraceStore.put("hypernova.whitening.method", "LowRankZCA");
        if (providerMismatch(runtimeProvider)) {
            traceSkipped("provider_mismatch");
            return vec;
        }
        if (stats == null) {
            traceSkipped("stats_unavailable");
            return vec;
        }
        float[] transformed = stats.transform(vec);
        if (transformed == null) {
            traceSkipped("stats_returned_null");
            return new float[0];
        }
        boolean applied = transformed != vec;
        TraceStore.put("hypernova.whitening.applied", applied);
        TraceStore.put("hypernova.whitening.skipReason", applied ? null : "not_ready_or_passthrough");
        TraceStore.put("hypernova.whitening.skippedReason", applied ? null : "not_ready_or_passthrough");
        TraceStore.put("hypernova.whitening.disabledReason", applied ? null : "not_ready_or_passthrough");
        return transformed;
    }

    private boolean providerMismatch(String runtimeProvider) {
        if (UNKNOWN_PROVIDER.equals(provider) || UNKNOWN_PROVIDER.equals(runtimeProvider)) {
            return false;
        }
        return !provider.equals(runtimeProvider);
    }

    private String runtimeProviderOrConfigured() {
        String runtime = firstTraceProvider("embed.provider", "embedding.provider", "emb_provider", "vector.embedding.provider");
        return UNKNOWN_PROVIDER.equals(runtime) ? provider : runtime;
    }

    private static String firstTraceProvider(String... keys) {
        if (keys == null) {
            return UNKNOWN_PROVIDER;
        }
        for (String key : keys) {
            Object value = TraceStore.get(key);
            String provider = normalizeProvider(value);
            if (!UNKNOWN_PROVIDER.equals(provider)) {
                return provider;
            }
        }
        return UNKNOWN_PROVIDER;
    }

    private static String normalizeProvider(Object value) {
        String label = SafeRedactor.traceLabelOrFallback(value, UNKNOWN_PROVIDER);
        if (label == null || label.isBlank() || label.startsWith("hash:")) {
            return UNKNOWN_PROVIDER;
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? UNKNOWN_PROVIDER : normalized;
    }

    private static void traceSkipped(String reason) {
        TraceStore.put("hypernova.whitening.applied", false);
        TraceStore.put("hypernova.whitening.skipReason", reason);
        TraceStore.put("hypernova.whitening.skippedReason", reason);
        TraceStore.put("hypernova.whitening.disabledReason", reason);
    }
}
