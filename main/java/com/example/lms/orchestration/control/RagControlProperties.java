package com.example.lms.orchestration.control;

/** Internal rollout thresholds. No environment or persistent configuration is introduced. */
public record RagControlProperties(
        int observationWindow,
        double maxGapOrUnclassifiedRate,
        long maxP95OverheadMillis) {

    public static final int DEFAULT_OBSERVATION_WINDOW = 300;
    public static final double DEFAULT_MAX_GAP_RATE = 0.01d;
    public static final long DEFAULT_MAX_P95_OVERHEAD_MILLIS = 20L;

    public RagControlProperties() {
        this(DEFAULT_OBSERVATION_WINDOW, DEFAULT_MAX_GAP_RATE, DEFAULT_MAX_P95_OVERHEAD_MILLIS);
    }

    public RagControlProperties {
        if (observationWindow < 1) {
            throw new IllegalArgumentException("observationWindow must be positive");
        }
        if (!Double.isFinite(maxGapOrUnclassifiedRate)
                || maxGapOrUnclassifiedRate < 0.0d
                || maxGapOrUnclassifiedRate > 1.0d) {
            throw new IllegalArgumentException("maxGapOrUnclassifiedRate must be in [0,1]");
        }
        if (maxP95OverheadMillis < 0L) {
            throw new IllegalArgumentException("maxP95OverheadMillis must be non-negative");
        }
    }
}
