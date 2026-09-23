package com.example.lms.service.disambiguation;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DisambiguationTraceSuppressions {
    private static final Logger log = LoggerFactory.getLogger(DisambiguationTraceSuppressions.class);

    private DisambiguationTraceSuppressions() {
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        try {
            TraceStore.put("disambiguation.suppressed." + safeStage, true);
            TraceStore.put("disambiguation.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[Disambig] suppression trace failed stage={} errorHash={} errorLength={}",
                    safeStage, SafeRedactor.hashValue(String.valueOf(traceFailure)),
                    String.valueOf(traceFailure).length());
        }
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
