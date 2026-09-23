package com.example.lms.service;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NaverTraceSuppressions {
    private static final Logger log = LoggerFactory.getLogger(NaverTraceSuppressions.class);

    private NaverTraceSuppressions() {
    }

    static void traceSuppressed(String stage, Throwable failure) {
        trace(stage, failure);
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        try {
            TraceStore.put("web.naver.suppressed." + safeStage, true);
            TraceStore.put("web.naver.suppressed." + safeStage + ".errorType", errorType(failure));
        } catch (RuntimeException traceFailure) {
            log.debug("[Naver] suppressed trace failed stage={} errorType={}",
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
