package com.example.lms.debug.ai;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read-only AI-assisted debugging metrics snapshot.
 */
public record DebugAiMetricSnapshot(
        int schemaVersion,
        Instant generatedAt,
        long windowMs,
        long totalEvents,
        long warnEvents,
        long errorEvents,
        Map<String, Long> probeCounts,
        Map<String, Long> layerCounts,
        Map<String, Long> failureClassCounts,
        List<Map<String, Object>> fingerprintHotspots,
        List<Map<String, Object>> usedDebugTools,
        List<Map<String, Object>> planUsage,
        List<DebugAiRawTile> tiles,
        Map<String, Object> scorecard,
        List<String> recommendations) {
}
