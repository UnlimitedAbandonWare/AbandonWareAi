package com.example.lms.service;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ChatWorkflowTraceSuppressions {
    private static final Logger log = LoggerFactory.getLogger(ChatWorkflowTraceSuppressions.class);

    private ChatWorkflowTraceSuppressions() {
    }

    static void traceSuppressed(String stage, Throwable failure) {
        trace(stage, failure);
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        try {
            TraceStore.put("chat.workflow.suppressed." + safeStage, true);
            TraceStore.put("chat.workflow.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[ChatWorkflow] suppressed trace failed stage={} errorType={}",
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
