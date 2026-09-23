package com.example.lms.debug.ai;

/**
 * Stable 9-tile rollup for debugging-layer health.
 */
public record DebugAiRawTile(
        int tileIndex,
        String tileName,
        long eventCount,
        long warnCount,
        long errorCount,
        String topFailureClass,
        String topFingerprintHash,
        long lastTsMs,
        String status) {
}
