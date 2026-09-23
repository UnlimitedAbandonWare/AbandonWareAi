package com.example.lms.debug;

/**
 * Aggregation counters attached to a {@link DebugEvent} when the event was
 * rate-limited/aggregated by fingerprint.
 */
public record DebugAgg(
        long windowMs,
        long windowCount,
        long suppressedInWindow,
        long windowFirstTsMs,
        long windowLastTsMs
) {
}
