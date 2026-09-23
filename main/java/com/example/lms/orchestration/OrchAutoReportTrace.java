package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class OrchAutoReportTrace {

    private OrchAutoReportTrace() {
    }

    static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("orch.autoReport.suppressed." + safeStage, true);
        TraceStore.put("orch.autoReport.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
