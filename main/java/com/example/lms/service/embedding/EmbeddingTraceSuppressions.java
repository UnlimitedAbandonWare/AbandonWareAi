package com.example.lms.service.embedding;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EmbeddingTraceSuppressions {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingTraceSuppressions.class);

    private EmbeddingTraceSuppressions() {
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        try {
            TraceStore.put("embed.suppressed." + safeStage, true);
            TraceStore.put("embed.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[Embedding] suppressed trace failed stage={} errorType={}",
                    safeStage, errorType(traceFailure));
        }
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
