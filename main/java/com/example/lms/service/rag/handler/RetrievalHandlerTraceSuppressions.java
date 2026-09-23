package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;

final class RetrievalHandlerTraceSuppressions {
    private RetrievalHandlerTraceSuppressions() {
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = suppressedFailureClass(failure);
        TraceStore.put("retrieval.handler.suppressed.stage", safeStage);
        TraceStore.put("retrieval.handler.suppressed.errorType", errorType);
        TraceStore.put("retrieval.handler.suppressed." + safeStage, true);
        TraceStore.put("retrieval.handler.suppressed." + safeStage + ".errorType", errorType);
    }

    static void traceSuppressed(Logger log, String component, String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeComponent = SafeRedactor.traceLabelOrFallback(component, "retrieval");
        String errorType = suppressedFailureClass(failure);
        if (log != null && log.isDebugEnabled()) {
            log.debug("[{}] fail-soft stage={} err={}", safeComponent, safeStage, errorType);
        }
        trace(stage, failure);
    }

    static long parseLong(Logger log, String component, String stage, Object value, long fallback) {
        if (value instanceof Number number) {
            return finite(number.doubleValue(), log, component, stage) ? number.longValue() : fallback;
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException failure) {
            traceSuppressed(log, component, stage, failure);
            return fallback;
        }
    }

    static int parseInt(Logger log, String component, String stage, Object value, int fallback) {
        if (value instanceof Number number) {
            return finite(number.doubleValue(), log, component, stage) ? number.intValue() : fallback;
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException failure) {
            traceSuppressed(log, component, stage, failure);
            return fallback;
        }
    }

    static double parseDouble(Logger log, String component, String stage, Object value, double fallback) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            return finite(parsed, log, component, stage) ? parsed : fallback;
        }
        if (value == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return finite(parsed, log, component, stage) ? parsed : fallback;
        } catch (NumberFormatException failure) {
            traceSuppressed(log, component, stage, failure);
            return fallback;
        }
    }

    private static boolean finite(double value, Logger log, String component, String stage) {
        if (Double.isFinite(value)) {
            return true;
        }
        traceSuppressed(log, component, stage, new NumberFormatException("non-finite"));
        return false;
    }

    private static String suppressedFailureClass(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        String name = failure.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("cancel") || name.contains("interrupt")) {
            return "cancelled";
        }
        if (name.contains("timeout")) {
            return "timeout";
        }
        if (name.contains("numberformat") || name.contains("parse")) {
            return "invalid_number";
        }
        return "silent-failure";
    }
}
