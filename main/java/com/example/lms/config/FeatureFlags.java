package com.example.lms.config;

import com.example.lms.search.TraceStore;

/**
 * Centralised feature flags with sensible defaults.
 */
public class FeatureFlags {
    public boolean bm25Enabled = Boolean.parseBoolean(System.getProperty("retrieval.bm25.enabled", "false"));
    public double rrfWeightWeb = parseDoubleProperty("fusion.rrf.weight.web", 1.0d);
    public double rrfWeightVector = parseDoubleProperty("fusion.rrf.weight.vector", 1.0d);
    public double rrfWeightKg = parseDoubleProperty("fusion.rrf.weight.kg", 0.8d);
    public double rrfWeightBm25 = parseDoubleProperty("fusion.rrf.weight.bm25", 1.0d);

    static double parseDoubleProperty(String name, double fallback) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ignore) {
            traceSuppressed("featureFlags.doubleProperty");
            return fallback;
        }
    }

    private static void traceSuppressed(String stage) {
        TraceStore.put("config.suppressed." + stage, true);
        TraceStore.put("config.suppressed." + stage + ".errorType", "invalid_number");
    }
}
