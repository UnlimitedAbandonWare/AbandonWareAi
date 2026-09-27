package com.example.lms.resilience;

import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

final class RagFailureBlackboxValues {
    private static final Logger LOG = LoggerFactory.getLogger(RagFailureBlackboxValues.class);

    private RagFailureBlackboxValues() {
    }

    static long toLong(Object value) {
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                traceSkipped("values_long_parse", null);
                return 0L;
            }
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            traceSkipped("values_long_parse", e);
            return 0L;
        }
    }

    static double asDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            traceSkipped("values_double_parse", null);
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            traceSkipped("values_double_parse", null);
            return fallback;
        } catch (NumberFormatException e) {
            traceSkipped("values_double_parse", e);
            return fallback;
        }
    }

    static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static boolean contains(String value, String needle) {
        return value != null && needle != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    static String safeLabel(String value, String fallback) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            s = fallback == null ? "" : fallback.trim().toLowerCase(Locale.ROOT);
        }
        s = s.replaceAll("[^a-z0-9_.:-]+", "_");
        if (s.length() > 96) {
            s = s.substring(0, 96);
        }
        return s.isBlank() ? "none" : s;
    }

    static String safePublicLabel(String value, String fallback) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank()) {
            raw = fallback == null ? "" : fallback.trim();
        }
        if (raw.isBlank()) {
            return "none";
        }
        String label = SafeRedactor.traceLabelOrFallback(raw, "");
        if (label == null || label.isBlank()) {
            return "none";
        }
        String token = label.toLowerCase(Locale.ROOT);
        if (token.matches("[a-z0-9_.:-]+")) {
            return token.length() > 96 ? token.substring(0, 96) : token;
        }
        return label;
    }

    static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }

    static double round4(double value) {
        return Math.round(clamp01(value) * 10000.0d) / 10000.0d;
    }

    static double round4Unbounded(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    static boolean llmClientBlankOrFailed(Map<String, Object> trace) {
        return truthy(trace, "llm.client.blank") || truthy(trace, "llm.client.failed")
                || positive(trace, "llm.client.blank.count") || positive(trace, "llm.client.failed.count");
    }

    static String llmRouteSilentReason(Map<String, Object> trace) {
        if (truthy(trace, "llm.client.failed") || positive(trace, "llm.client.failed.count")) {
            return "llm_client_failed_" + safeLabel(firstPresent(trace, "llm.client.stage"), "unknown");
        }
        return "llm_blank_" + safeLabel(firstPresent(trace,
                "llm.output.doneReason", "llm.output.reason", "llm.error.code", "llm.client.stage"), "unknown");
    }

    private static boolean truthy(Map<String, Object> trace, String key) {
        Object value = trace == null ? null : trace.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(asString(value)) || "1".equals(asString(value));
    }

    private static boolean positive(Map<String, Object> trace, String key) {
        return asDouble(trace == null ? null : trace.get(key), 0.0d) > 0.0d;
    }

    private static String firstPresent(Map<String, Object> trace, String... keys) {
        if (trace == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            String value = asString(trace.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static void traceSkipped(String stage, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        LOG.debug("[AWX][blackbox][values] trace skipped stage={} errorType={}",
                safeStage,
                safeErrorType);
    }
}
