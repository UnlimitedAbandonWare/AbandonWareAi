package com.example.lms.moe;

/**
 * Minimal metrics for an offline soak run.
 */
public record RgbSoakMetrics(
        int queries,
        double retrievalHitRate,
        double evidenceCoverage,
        double avgLatencyMs,
        int calls,
        int blueCalls,
        double fallbackRate,
        int docCount,
        int evidenceCount,
        int distinctSources,
        double avgDocsPerQuery,
        NormalizedRagMetrics normalized
) {
    public RgbSoakMetrics(
            int queries,
            double retrievalHitRate,
            double evidenceCoverage,
            double avgLatencyMs,
            int calls,
            int blueCalls,
            double fallbackRate) {
        this(
                queries,
                retrievalHitRate,
                evidenceCoverage,
                avgLatencyMs,
                calls,
                blueCalls,
                fallbackRate,
                0,
                0,
                0,
                0.0d,
                NormalizedRagMetrics.fromRates(
                        retrievalHitRate,
                        evidenceCoverage,
                        0.0d,
                        0.0d,
                        avgLatencyMs,
                        fallbackRate));
    }
}
