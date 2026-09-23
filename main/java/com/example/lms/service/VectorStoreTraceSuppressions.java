package com.example.lms.service;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class VectorStoreTraceSuppressions {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreTraceSuppressions.class);

    private VectorStoreTraceSuppressions() {
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        try {
            TraceStore.put("ml.vector.suppressed." + safeStage, true);
            TraceStore.put("ml.vector.suppressed." + safeStage + ".errorType", errorType(failure));
        } catch (RuntimeException traceFailure) {
            log.debug("[VectorStore] suppression trace failed stage={} errorHash={} errorLength={}",
                    safeStage, SafeRedactor.hashValue(String.valueOf(traceFailure)),
                    String.valueOf(traceFailure).length());
        }
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
