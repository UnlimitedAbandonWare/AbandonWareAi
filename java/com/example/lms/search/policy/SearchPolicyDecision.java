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
        String reason) {

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
                reason == null ? "" : reason);
    }
}
