package com.example.lms.service.web;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class BraveSearchTraceSuppressions {
    private BraveSearchTraceSuppressions() {
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("web.brave.suppressed.stage", safeStage);
        TraceStore.put("web.brave.suppressed.errorType", safeErrorType);
        TraceStore.put("web.brave.suppressed." + safeStage, true);
        TraceStore.put("web.brave.suppressed." + safeStage + ".errorType", safeErrorType);
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
