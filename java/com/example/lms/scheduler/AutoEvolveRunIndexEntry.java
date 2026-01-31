package com.example.lms.scheduler;

import java.time.Instant;

/**
 * Lightweight summary row for persisted auto-evolve history.
 *
 * <p>This is intended for fast status UIs (index file) without loading
 * the entire ndjson history into memory.</p>
 */
public record AutoEvolveRunIndexEntry(
        String sessionId,
        String trigger,
        AutoEvolveRunDebug.Outcome outcome,
        Instant startedAt,
        Instant endedAt,
        String primaryStrategy,
        Boolean blueAttempted,
        Boolean blueSuccess,
        Integer blueHttpStatus,
        Long blueLatencyMs,
        String errorClass,
        String errorMessage,
        String ndjsonFile
) {

    public static AutoEvolveRunIndexEntry from(AutoEvolveRunDebug d, String ndjsonFile) {
        if (d == null) return null;

        String primary = null;
        try {
            if (d.decision() != null && d.decision().primaryStrategy() != null) {
                primary = String.valueOf(d.decision().primaryStrategy());
            }
        } catch (Exception ignore) {
            // ignore
        }

        AutoEvolveRunDebug.BlueCallDebug b = d.blueCall();
        Boolean attempted = b == null ? null : b.attempted();
        Boolean success = b == null ? null : b.success();
        Integer status = b == null ? null : b.httpStatus();
        Long latency = b == null ? null : b.latencyMs();

        return new AutoEvolveRunIndexEntry(
                d.sessionId(),
                d.trigger(),
                d.outcome(),
                d.startedAt(),
                d.endedAt(),
                primary,
                attempted,
                success,
                status,
                latency,
                d.errorClass(),
                d.errorMessage(),
                ndjsonFile
        );
    }
}
