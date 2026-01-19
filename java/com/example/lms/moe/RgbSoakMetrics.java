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
        double fallbackRate
) {}
