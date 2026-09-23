package com.example.lms.service;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class MemoryReinforcementTraceSuppressions {
    private MemoryReinforcementTraceSuppressions() {
    }

    static void traceSuppressed(String stage, Throwable failure) {
        trace(stage, failure);
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        TraceStore.put("memory.reinforce.suppressed.stage", safeStage);
        TraceStore.put("memory.reinforce.suppressed.errorType", errorType);
        TraceStore.put("memory.reinforce.suppressed." + safeStage, true);
        TraceStore.put("memory.reinforce.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
