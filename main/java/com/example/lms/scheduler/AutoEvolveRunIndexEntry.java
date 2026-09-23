package com.example.lms.scheduler;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.time.Instant;

/**
 * Lightweight summary row for persisted auto-evolve history.
 *
 * <p>This is intended for fast status UIs (index file) without loading
 * the entire ndjson history into memory.</p>
 */
public record AutoEvolveRunIndexEntry(
        String sessionHash,
        String trigger,
        AutoEvolveRunDebug.Outcome outcome,
        Instant startedAt,
        Instant endedAt,
        String primaryStrategy,
        Boolean blueAttempted,
        Boolean blueSuccess,
        Integer blueHttpStatus,
        Long blueLatencyMs,
        boolean errorClassPresent,
        String errorClassHash,
        int errorClassLength,
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
            TraceStore.put("autoevolve.index.suppressed.primaryStrategy", true);
            TraceStore.put("autoevolve.index.suppressed.primaryStrategy.errorType",
                    ignore.getClass().getSimpleName());
            // ignore
        }

        AutoEvolveRunDebug.BlueCallDebug b = d.blueCall();
        Boolean attempted = b == null ? null : b.attempted();
        Boolean success = b == null ? null : b.success();
        Integer status = b == null ? null : b.httpStatus();
        Long latency = b == null ? null : b.latencyMs();
        String errorClass = d.errorClass();

        return new AutoEvolveRunIndexEntry(
                SafeRedactor.hashValue(d.sessionId()),
                SafeRedactor.traceLabelOrFallback(d.trigger(), ""),
                d.outcome(),
                d.startedAt(),
                d.endedAt(),
                primary,
                attempted,
                success,
                status,
                latency,
                errorClass != null && !errorClass.isBlank(),
                hashOrEmpty(errorClass),
                errorClass == null ? 0 : errorClass.length(),
                SafeRedactor.diagnosticText("autoevolve.index.errorMessage", d.errorMessage(), 180),
                ndjsonFile
        );
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }
}
