package com.example.lms.agent.context;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
final class AgentPipelineHealthTrace {

    private AgentPipelineHealthTrace() {
    }

    static void traceSuppressed(String stage, Throwable ex) {
        String safeStage = firstNonBlank(SafeRedactor.traceLabelOrFallback(stage, null), "unknown");
        String errorType = firstNonBlank(
                SafeRedactor.traceLabelOrFallback(ex == null ? null : ex.getClass().getSimpleName(), null),
                "unknown");
        try {
            TraceStore.put("agent.pipelineHealth.suppressed.stage", safeStage);
            TraceStore.put("agent.pipelineHealth.suppressed.errorType", errorType);
            TraceStore.put("agent.pipelineHealth.suppressed." + safeStage, true);
            TraceStore.put("agent.pipelineHealth.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[AWX][agent][pipeline] suppressed trace failed stage={} errorType={}",
                    safeStage,
                    SafeRedactor.traceLabelOrFallback(traceFailure.getClass().getSimpleName(), "unknown"));
        }
    }

    static int traceZero(String stage, Throwable ex) {
        traceSuppressed(stage, ex);
        return 0;
    }

    static Integer traceNullInteger(String stage, Throwable ex) {
        traceSuppressed(stage, ex);
        return null;
    }

    static boolean traceFalse(String stage, Throwable ex) {
        traceSuppressed(stage, ex);
        return false;
    }

    static boolean traceTrue(String stage, Throwable ex) {
        traceSuppressed(stage, ex);
        return true;
    }

    static Map<String, Object> traceEmptyMap(String stage, Throwable ex) {
        traceSuppressed(stage, ex);
        return Map.of();
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
