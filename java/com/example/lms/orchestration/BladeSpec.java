package com.example.lms.orchestration;

/**
 * UAW: BladeSpec defines feature restrictions per orchestration mode.
 */
public record BladeSpec(
        boolean disableKeywordSelection,
        boolean disableSelfAsk,
        boolean disableAnalyze,
        boolean disableCrossEncoder,
        int maxPlannerBranches,
        int maxQueryBurst,
        String label
) {
    public static BladeSpec normal() {
        return new BladeSpec(false, false, false, false, 5, 4, "NORMAL");
    }

    public static BladeSpec strike() {
        return new BladeSpec(true, true, true, false, 2, 6, "STRIKE");
    }

    public static BladeSpec bypass() {
        return new BladeSpec(true, true, true, true, 1, 8, "BYPASS");
    }
}
