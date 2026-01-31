package com.example.lms.uaw.autolearn;

/**
 * Result of a single UAW autolearn cycle.
 */
public record AutoLearnCycleResult(
        int attempted,
        int acceptedCount,
        boolean abortedByUser,
        String datasetPath
) {
}
