package com.example.lms.service.rag.query;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable query-analysis DTO used to drive dynamic retrieval and prompt policy.
 */
public record QueryAnalysisResult(
        String originalQuery,
        QueryIntent intent,
        List<String> entities,
        List<String> expandedKeywords,
        boolean wantsFresh,
        boolean isExploration,
        List<String> searchQueries,
        double confidenceScore,
        double decisionValueScore,
        double optimismScore,
        String resourceTier,
        String expectedDomain,
        List<String> contextHints,
        List<String> noiseDomains) {

    public static final double DEFAULT_OPTIMISM_DAMPING = 0.35d;

    public enum QueryIntent {
        SEARCH,
        INFO,
        COMPARE,
        TRENDING,
        GENERAL
    }

    public QueryAnalysisResult {
        entities = entities != null ? List.copyOf(entities) : Collections.emptyList();
        expandedKeywords = expandedKeywords != null ? List.copyOf(expandedKeywords) : Collections.emptyList();
        searchQueries = searchQueries != null ? List.copyOf(searchQueries) : Collections.emptyList();
        contextHints = contextHints != null ? List.copyOf(contextHints) : Collections.emptyList();
        noiseDomains = noiseDomains != null ? List.copyOf(noiseDomains) : Collections.emptyList();
        intent = intent != null ? intent : QueryIntent.GENERAL;
        confidenceScore = clamp01(confidenceScore);
        decisionValueScore = clamp01(decisionValueScore);
        optimismScore = clamp01(optimismScore);
        resourceTier = normalizeTier(resourceTier, decisionValueScore);
    }

    public boolean isEntityQuery() {
        return (intent == QueryIntent.SEARCH || intent == QueryIntent.INFO)
                && entities != null
                && !entities.isEmpty()
                && expectedDomain != null
                && !expectedDomain.isBlank();
    }

    public double getDynamicThreshold() {
        double baseThreshold = switch (resourceTier) {
            case "CRITICAL" -> 0.48d;
            case "HIGH" -> 0.42d;
            case "LOW" -> 0.18d;
            default -> 0.30d;
        };
        double explorationAdjustment = isExploration ? 0.06d : 0.0d;
        double confidenceAdjustment = riskAdjustedConfidence() * 0.08d;
        double threshold = baseThreshold - explorationAdjustment - confidenceAdjustment;
        return switch (resourceTier) {
            case "CRITICAL" -> clamp(threshold, 0.30d, 0.55d);
            case "HIGH" -> clamp(threshold, 0.24d, 0.48d);
            case "LOW" -> clamp(threshold, 0.10d, 0.28d);
            default -> clamp(threshold, 0.15d, 0.38d);
        };
    }

    public double riskAdjustedConfidence() {
        return riskAdjustedConfidence(DEFAULT_OPTIMISM_DAMPING);
    }

    public double riskAdjustedConfidence(double optimismDamping) {
        double damping = clamp01(optimismDamping);
        return clamp01(confidenceScore * (1.0d - damping * optimismScore * decisionValueScore));
    }

    public double rewriteTemperature() {
        return clamp(0.55d - 0.40d * decisionValueScore, 0.12d, 0.55d);
    }

    public double searchRangeMultiplier() {
        double base = switch (resourceTier) {
            case "CRITICAL" -> 1.70d;
            case "HIGH" -> 1.40d;
            case "LOW" -> 0.75d;
            default -> 1.00d;
        };
        double freshnessBoost = wantsFresh ? 0.10d : 0.0d;
        double explorationBoost = isExploration ? 0.10d : 0.0d;
        return clamp(base + freshnessBoost + explorationBoost, 0.60d, 2.00d);
    }

    public static QueryAnalysisResult empty(String query) {
        return new QueryAnalysisResult(
                query,
                QueryIntent.GENERAL,
                Collections.emptyList(),
                Collections.emptyList(),
                false,
                false,
                Collections.emptyList(),
                0.0d,
                0.0d,
                0.0d,
                "LOW",
                null,
                Collections.emptyList(),
                Collections.emptyList());
    }

    public static QueryAnalysisResult explorationFallback(String query) {
        return new QueryAnalysisResult(
                query,
                QueryIntent.SEARCH,
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                true,
                List.of(query),
                0.5d,
                0.55d,
                0.45d,
                "MEDIUM",
                null,
                Collections.emptyList(),
                Collections.emptyList());
    }

    private static String normalizeTier(String tier, double valueScore) {
        String normalized = tier == null ? "" : tier.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> {
                if (valueScore >= 0.90d) yield "CRITICAL";
                if (valueScore >= 0.65d) yield "HIGH";
                if (valueScore <= 0.25d) yield "LOW";
                yield "MEDIUM";
            }
        };
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0d, 1.0d);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
