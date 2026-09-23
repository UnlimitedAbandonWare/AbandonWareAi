package com.example.lms.agent;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AgentTraceSuppressions {
    private static final Logger log = LoggerFactory.getLogger(AgentTraceSuppressions.class);

    private AgentTraceSuppressions() {
    }

    static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        try {
            TraceStore.put("agent.suppressed.stage", safeStage);
            TraceStore.put("agent.suppressed.errorType", errorType);
            TraceStore.put("agent.suppressed." + safeStage, true);
            TraceStore.put("agent.suppressed." + safeStage + ".errorType", errorType);
            TraceStore.inc("agent.suppressed.count");
        } catch (Throwable traceFailure) {
            log.debug("[AWX][agent] suppressed trace failed stage={} errorType={}",
                    safeStage,
                    errorType(traceFailure));
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
