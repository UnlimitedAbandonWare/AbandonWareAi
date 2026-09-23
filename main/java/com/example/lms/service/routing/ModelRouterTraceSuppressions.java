package com.example.lms.service.routing;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ModelRouterTraceSuppressions {
    private static final Logger log = LoggerFactory.getLogger(ModelRouterTraceSuppressions.class);

    private ModelRouterTraceSuppressions() {
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        try {
            TraceStore.put("ml.router.suppressed." + safeStage, true);
            TraceStore.put("ml.router.suppressed." + safeStage + ".errorType", errorType(failure));
        } catch (RuntimeException traceFailure) {
            log.debug("[ModelRouter] suppressed trace failed stage={} errorType={}",
                    safeStage, errorType(traceFailure));
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
