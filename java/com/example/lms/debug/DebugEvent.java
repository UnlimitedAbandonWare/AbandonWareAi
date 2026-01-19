package com.example.lms.debug;

import java.time.Instant;
import java.util.Map;

/**
 * Structured debug event.
 *
 * <p>
 * This is intentionally small and JSON-friendly, so it can be:
 * <ul>
 *   <li>logged as a single line JSON to console</li>
 *   <li>served via an in-memory diagnostics endpoint</li>
 * </ul>
 * </p>
 */
public record DebugEvent(
        String id,
        Instant ts,
        long tsMs,
        DebugEventLevel level,
        DebugProbeType probe,
        String fingerprint,
        String message,
        String sid,
        String traceId,
        String requestId,
        String thread,
        String where,
        Map<String, Object> data,
        DebugError error,
        DebugAgg agg
) {
}
