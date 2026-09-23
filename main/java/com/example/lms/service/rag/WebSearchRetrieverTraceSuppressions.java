package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class WebSearchRetrieverTraceSuppressions {
    private WebSearchRetrieverTraceSuppressions() {
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        TraceStore.put("webSearch.suppressed.stage", safeStage);
        TraceStore.put("webSearch.suppressed.errorType", errorType);
        TraceStore.put("webSearch.suppressed." + safeStage, true);
        TraceStore.put("webSearch.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
