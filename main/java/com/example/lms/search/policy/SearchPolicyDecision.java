package com.example.lms.search.policy;

/**
 * Result of SearchPolicyEngine decision.
 */
public record SearchPolicyDecision(
        SearchPolicyMode mode,
        boolean slicingEnabled,
        boolean expansionEnabled,
        int maxFinalQueries,
        int sliceWindowSentences,
        int sliceOverlapSentences,
        int maxSlices,
        int maxExpansions,
        double webTopKMultiplier,
        double vecTopKMultiplier,
        String reason,
        double rewriteValidationTemperature,
        double rewriteExplorationTemperature,
        double rewriteExplorationRate,
        String rewriteTemperatureProfile) {

    public SearchPolicyDecision(
            SearchPolicyMode mode,
            boolean slicingEnabled,
            boolean expansionEnabled,
            int maxFinalQueries,
            int sliceWindowSentences,
            int sliceOverlapSentences,
            int maxSlices,
            int maxExpansions,
            double webTopKMultiplier,
            double vecTopKMultiplier,
            String reason) {
        this(mode,
                slicingEnabled,
                expansionEnabled,
                maxFinalQueries,
                sliceWindowSentences,
                sliceOverlapSentences,
                maxSlices,
                maxExpansions,
                webTopKMultiplier,
                vecTopKMultiplier,
                reason,
                defaultValidationTemperature(mode),
                defaultExplorationTemperature(mode),
                defaultExplorationRate(mode),
                defaultTemperatureProfile(mode));
    }

    public SearchPolicyDecision {
        mode = mode == null ? SearchPolicyMode.OFF : mode;
        reason = reason == null ? "" : reason;
        rewriteValidationTemperature = clampDouble(rewriteValidationTemperature, 0.0d, 1.0d);
        rewriteExplorationTemperature = clampDouble(rewriteExplorationTemperature, 0.0d, 1.0d);
        rewriteExplorationRate = clampDouble(rewriteExplorationRate, 0.0d, 1.0d);
        rewriteTemperatureProfile = (rewriteTemperatureProfile == null || rewriteTemperatureProfile.isBlank())
                ? defaultTemperatureProfile(mode)
                : rewriteTemperatureProfile.trim();
    }

    public static SearchPolicyDecision off(String reason) {
        return new SearchPolicyDecision(
                SearchPolicyMode.OFF,
                false,
                false,
                8,
                2,
                1,
                4,
                0,
                1.0,
                1.0,
                reason == null ? "" : reason,
                0.0d,
                0.0d,
                0.0d,
                "disabled");
    }

    private static double defaultValidationTemperature(SearchPolicyMode mode) {
        return switch (mode == null ? SearchPolicyMode.OFF : mode) {
            case OFF -> 0.0d;
            case PRECISION -> 0.12d;
            case BALANCED -> 0.18d;
            case RECALL -> 0.22d;
            case DISAMBIGUATE -> 0.16d;
        };
    }

    private static double defaultExplorationTemperature(SearchPolicyMode mode) {
        return switch (mode == null ? SearchPolicyMode.OFF : mode) {
            case OFF -> 0.0d;
            case PRECISION -> 0.22d;
            case BALANCED -> 0.42d;
            case RECALL -> 0.68d;
            case DISAMBIGUATE -> 0.38d;
        };
    }

    private static double defaultExplorationRate(SearchPolicyMode mode) {
        return switch (mode == null ? SearchPolicyMode.OFF : mode) {
            case OFF -> 0.0d;
            case PRECISION -> 0.08d;
            case BALANCED -> 0.18d;
            case RECALL -> 0.30d;
            case DISAMBIGUATE -> 0.14d;
        };
    }

    private static String defaultTemperatureProfile(SearchPolicyMode mode) {
        return switch (mode == null ? SearchPolicyMode.OFF : mode) {
            case OFF -> "disabled";
            case PRECISION -> "conservative";
            case BALANCED -> "balanced";
            case RECALL -> "exploratory";
            case DISAMBIGUATE -> "disambiguate";
        };
    }

    private static double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
