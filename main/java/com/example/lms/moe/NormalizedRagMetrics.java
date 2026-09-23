package com.example.lms.moe;

import com.example.lms.metrics.FaithfulnessMetricSnapshotStore;
import com.example.lms.search.TraceStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic RAG quality/cost metrics normalized to [0,1].
 */
public record NormalizedRagMetrics(
        double retrievalHitRate,
        double evidenceCoverage,
        double sourceDiversity,
        double resultDepth,
        double latencyCost,
        double latencyQuality,
        double fallbackCost,
        double fallbackStability,
        double balancedScore
) {
    public static final String SCHEMA_VERSION = "rag-neutral-v1";
    public static final double MIN_RETRIEVAL_HIT_RATE = 0.60d;
    public static final double MIN_EVIDENCE_COVERAGE = 0.70d;
    public static final double MIN_SOURCE_DIVERSITY = 0.35d;
    public static final int MIN_SOURCE_COLLAPSE_DOCS = 3;
    public static final double MIN_RESULT_DEPTH = 0.25d;
    public static final double MAX_LATENCY_COST = 0.70d;
    public static final double MAX_FALLBACK_COST = 0.30d;
    private static final double LATENCY_SOFT_CAP_MS = 3000.0d;

    public NormalizedRagMetrics {
        retrievalHitRate = clip01(retrievalHitRate);
        evidenceCoverage = clip01(evidenceCoverage);
        sourceDiversity = clip01(sourceDiversity);
        resultDepth = clip01(resultDepth);
        latencyCost = clip01(latencyCost);
        latencyQuality = clip01(latencyQuality);
        fallbackCost = clip01(fallbackCost);
        fallbackStability = clip01(fallbackStability);
        balancedScore = clip01(balancedScore);
    }

    public static NormalizedRagMetrics from(
            int calls,
            int hits,
            int docCount,
            int evidenceCount,
            Map<String, Integer> sourceCounts,
            double avgDocsPerQuery,
            int topK,
            double avgLatencyMs,
            double fallbackRate) {

        double retrievalHitRate = ratio(hits, calls);
        double evidenceCoverage = ratio(evidenceCount, docCount);
        double sourceDiversity = normalizedSimpson(sourceCounts, docCount);
        double resultDepth = ratio(avgDocsPerQuery, Math.max(1, topK));
        double latencyCost = Math.tanh(Math.max(0.0d, avgLatencyMs) / LATENCY_SOFT_CAP_MS);
        double latencyQuality = 1.0d - latencyCost;
        double fallbackCost = clip01(fallbackRate);
        double fallbackStability = 1.0d - fallbackCost;
        double balancedScore = mean(
                retrievalHitRate,
                evidenceCoverage,
                sourceDiversity,
                resultDepth,
                latencyQuality,
                fallbackStability);

        return new NormalizedRagMetrics(
                retrievalHitRate,
                evidenceCoverage,
                sourceDiversity,
                resultDepth,
                latencyCost,
                latencyQuality,
                fallbackCost,
                fallbackStability,
                balancedScore);
    }

    public static Map<String, Object> defaultThresholdMap() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("retrievalHitRate.min", MIN_RETRIEVAL_HIT_RATE);
        thresholds.put("evidenceCoverage.min", MIN_EVIDENCE_COVERAGE);
        thresholds.put("sourceDiversity.min", MIN_SOURCE_DIVERSITY);
        thresholds.put("sourceCollapse.docCount.min", MIN_SOURCE_COLLAPSE_DOCS);
        thresholds.put("resultDepth.min", MIN_RESULT_DEPTH);
        thresholds.put("latencyCost.max", MAX_LATENCY_COST);
        thresholds.put("fallbackCost.max", MAX_FALLBACK_COST);
        return thresholds;
    }

    public static List<Map<String, Object>> thresholdBreaks(NormalizedRagMetrics metrics, int docCount) {
        if (metrics == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        addMinBreak(out, "retrieval_starvation", "retrievalHitRate",
                metrics.retrievalHitRate(), MIN_RETRIEVAL_HIT_RATE, "retrieval");
        addMinBreak(out, "weak_evidence", "evidenceCoverage",
                metrics.evidenceCoverage(), MIN_EVIDENCE_COVERAGE, "evidence");
        if (Math.max(0, docCount) >= MIN_SOURCE_COLLAPSE_DOCS) {
            addMinBreak(out, "source_collapse", "sourceDiversity",
                    metrics.sourceDiversity(), MIN_SOURCE_DIVERSITY, "fusion");
        }
        addMinBreak(out, "thin_results", "resultDepth",
                metrics.resultDepth(), MIN_RESULT_DEPTH, "final");
        addMaxBreak(out, "latency_pressure", "latencyCost",
                metrics.latencyCost(), MAX_LATENCY_COST, "runtime");
        addMaxBreak(out, "fallback_dependency", "fallbackCost",
                metrics.fallbackCost(), MAX_FALLBACK_COST, "fallback");
        return List.copyOf(out);
    }

    public static Map<String, Object> thresholdBreak(
            String label,
            String metric,
            double value,
            double threshold,
            String comparator,
            String stage) {
        double severity = ">=".equals(comparator) || ">".equals(comparator)
                ? Math.max(0.0d, threshold - value)
                : Math.max(0.0d, value - threshold);
        return thresholdBreak(label, metric, value, threshold, comparator, severity, stage);
    }

    public static Map<String, Object> thresholdBreak(
            String label,
            String metric,
            double value,
            double threshold,
            String comparator,
            double severity,
            String stage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", safe(label));
        row.put("metric", safe(metric));
        row.put("value", round4(value));
        row.put("threshold", round4(threshold));
        row.put("comparator", comparator == null || comparator.isBlank() ? ">=" : comparator);
        row.put("severity", round4(Math.max(0.0d, severity)));
        row.put("stage", safe(stage));
        return row;
    }

    public static NormalizedRagMetrics fromRates(
            double retrievalHitRate,
            double evidenceCoverage,
            double sourceDiversity,
            double resultDepth,
            double avgLatencyMs,
            double fallbackRate) {
        double latencyCost = Math.tanh(Math.max(0.0d, avgLatencyMs) / LATENCY_SOFT_CAP_MS);
        double latencyQuality = 1.0d - latencyCost;
        double fallbackCost = clip01(fallbackRate);
        double fallbackStability = 1.0d - fallbackCost;
        double balancedScore = mean(
                retrievalHitRate,
                evidenceCoverage,
                sourceDiversity,
                resultDepth,
                latencyQuality,
                fallbackStability);
        return new NormalizedRagMetrics(
                retrievalHitRate,
                evidenceCoverage,
                sourceDiversity,
                resultDepth,
                latencyCost,
                latencyQuality,
                fallbackCost,
                fallbackStability,
                balancedScore);
    }

    public NormalizedRagMetrics putTrace() {
        putTracePrefix("rag.eval.normalized.", true);
        putTracePrefix("rag.metrics.normalized.", false);
        return this;
    }

    private void putTracePrefix(String prefix, boolean includeSchemaVersion) {
        putMetricTrace(prefix + "retrievalHitRate", round4(retrievalHitRate));
        putMetricTrace(prefix + "evidenceCoverage", round4(evidenceCoverage));
        putMetricTrace(prefix + "sourceDiversity", round4(sourceDiversity));
        putMetricTrace(prefix + "resultDepth", round4(resultDepth));
        putMetricTrace(prefix + "latencyCost", round4(latencyCost));
        putMetricTrace(prefix + "fallbackCost", round4(fallbackCost));
        putMetricTrace(prefix + "balancedScore", round4(balancedScore));
        if (includeSchemaVersion) {
            putMetricTrace(prefix + "schemaVersion", SCHEMA_VERSION);
        }
    }

    private static void putMetricTrace(String key, Object value) {
        TraceStore.put(key, value);
        FaithfulnessMetricSnapshotStore.put(key, value);
    }

    private static double normalizedSimpson(Map<String, Integer> sourceCounts, int docCount) {
        if (sourceCounts == null || sourceCounts.size() <= 1 || docCount <= 1) {
            return 0.0d;
        }
        double sumSquares = 0.0d;
        for (Integer count : sourceCounts.values()) {
            if (count == null || count <= 0) {
                continue;
            }
            double p = count / (double) docCount;
            sumSquares += p * p;
        }
        int distinctSources = (int) sourceCounts.values().stream()
                .filter(v -> v != null && v > 0)
                .count();
        if (distinctSources <= 1) {
            return 0.0d;
        }
        double raw = 1.0d - sumSquares;
        double max = 1.0d - (1.0d / distinctSources);
        return max <= 0.0d ? 0.0d : clip01(raw / max);
    }

    private static double mean(double... values) {
        if (values == null || values.length == 0) {
            return 0.0d;
        }
        double sum = 0.0d;
        for (double value : values) {
            sum += clip01(value);
        }
        return sum / values.length;
    }

    private static double ratio(double numerator, double denominator) {
        if (denominator <= 0.0d) {
            return 0.0d;
        }
        return clip01(numerator / denominator);
    }

    private static void addMinBreak(List<Map<String, Object>> out,
                                    String label,
                                    String metric,
                                    double value,
                                    double threshold,
                                    String stage) {
        if (value < threshold) {
            out.add(thresholdBreak(label, metric, value, threshold, ">=", stage));
        }
    }

    private static void addMaxBreak(List<Map<String, Object>> out,
                                    String label,
                                    String metric,
                                    double value,
                                    double threshold,
                                    String stage) {
        if (value > threshold) {
            out.add(thresholdBreak(label, metric, value, threshold, "<=", stage));
        }
    }

    private static double round4(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static double clip01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0d) {
            return 0.0d;
        }
        if (value >= 1.0d) {
            return 1.0d;
        }
        return value;
    }
}
