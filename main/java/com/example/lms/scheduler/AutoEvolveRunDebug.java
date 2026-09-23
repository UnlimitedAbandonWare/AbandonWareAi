package com.example.lms.scheduler;

import com.example.lms.moe.RgbLogSignalParser;
import com.example.lms.moe.RgbResourceProbe;
import com.example.lms.moe.RgbSoakReport;
import com.example.lms.moe.RgbStrategySelector;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * In-memory debug record for a single auto-evolve run.
 *
 * <p>Kept intentionally small-ish: enough for ops/debug, not for analytics.
 * Stored in a bounded ring buffer, and optionally persisted to disk (ndjson + summary index)
 * when {@code rgb.moe.debug.persist-enabled=true}.
 */
public record AutoEvolveRunDebug(
        String sessionId,
        String trigger,
        boolean requireIdle,
        Boolean idleSatisfied,
        Outcome outcome,
        Instant startedAt,
        Instant endedAt,
        RgbLogSignalParser.Features logFeatures,
        RgbResourceProbe.Snapshot resourceSnapshot,
        RgbStrategySelector.Decision decision,
        List<String> baseQueries,
        List<String> finalQueries,
        ExpansionDebug greenExpansion,
        BlueCallDebug blueCall,
        String reportFile,
        RgbSoakReport report,
        String errorClass,
        String errorMessage
) {

    public enum Outcome {
        SUCCESS,
        SKIPPED_NOT_IDLE,
        SKIPPED_ALREADY_RUNNING,
        FAILED
    }

    public record ExpansionDebug(
            boolean attempted,
            long latencyMs,
            int inCount,
            int outCount,
            String errorClass,
            String errorMessage
    ) {
        public static ExpansionDebug skipped(int inCount) {
            return new ExpansionDebug(false, 0L, inCount, 0, null, null);
        }
    }

    /**
     * BLUE(Gemini) call debug: captures HTTP status/latency/retry-after when available.
     */
    public record BlueCallDebug(
            boolean attempted,
            boolean success,
            long latencyMs,
            int cap,
            int outCount,
            Integer httpStatus,
            String retryAfter,
            Map<String, String> responseHeaders,
            String errorClass,
            String errorMessage,
            String responseBodyPreview,
            boolean cooldownApplied
    ) {
        public static BlueCallDebug skipped() {
            return new BlueCallDebug(false, false, 0L, 0, 0, null, null,
                    Map.of(), null, null, null, false);
        }
    }
}
