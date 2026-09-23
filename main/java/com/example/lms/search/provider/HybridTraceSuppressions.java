package com.example.lms.search.provider;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HybridTraceSuppressions {
    private static final Logger log = LoggerFactory.getLogger(HybridTraceSuppressions.class);

    private HybridTraceSuppressions() {
    }

    static void traceSuppressed(String stage, Throwable failure) {
        trace(stage, failure);
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(safeStage, failure);
        try {
            TraceStore.put("web.hybrid.suppressed." + safeStage, true);
            TraceStore.put("web.hybrid.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[Hybrid] suppressed trace failed stage={} errorType={}",
                    safeStage, traceFailure.getClass().getSimpleName());
        }
    }

    private static String errorType(String safeStage, Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        String stage = safeStage == null ? "" : safeStage.toLowerCase(java.util.Locale.ROOT);
        if (failure instanceof java.time.DateTimeException || stage.contains("date")) {
            return "invalid_date";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
