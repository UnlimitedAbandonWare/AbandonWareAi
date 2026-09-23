package com.example.lms.search.probe;

/**
 * Represents the contribution of needle probe retrieval to final results.
 * Used for reinforcement learning and reward signal computation.
 */
public record NeedleContribution(
        int docsAdded,
        int docsUsedInTopN,
        double qualityDelta,
        boolean triggered) {
    /**
     * Creates an empty contribution (needle was not triggered).
     */
    public static NeedleContribution empty() {
        return new NeedleContribution(0, 0, 0.0, false);
    }

    /**
     * Creates a contribution record for a triggered needle probe.
     *
     * @param docsAdded      number of documents added by needle
     * @param docsUsedInTopN number of needle docs that made it to top-N
     * @param qualityDelta   improvement in evidence quality
     * @return contribution record
     */
    public static NeedleContribution of(int docsAdded, int docsUsedInTopN, double qualityDelta) {
        return new NeedleContribution(docsAdded, docsUsedInTopN, qualityDelta, true);
    }

    /**
     * Check if the needle probe was effective (contributed to final results).
     */
    public boolean isEffective() {
        return triggered && docsUsedInTopN > 0;
    }
}
