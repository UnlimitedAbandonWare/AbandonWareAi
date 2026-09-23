package com.example.lms.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SelfAskNumbers {
    private static final Logger log = LoggerFactory.getLogger(SelfAskNumbers.class);

    private SelfAskNumbers() {
    }

    static double parseDouble(Object value, double defaultValue) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return numeric;
            }
            log.debug("[SelfAskNumbers] fail-soft stage={} errorType={}", "parseDouble", "invalid_number");
            return defaultValue;
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            log.debug("[SelfAskNumbers] fail-soft stage={} errorType={}", "parseDouble", "invalid_number");
            return defaultValue;
        } catch (NumberFormatException ignore) {
            log.debug("[SelfAskNumbers] fail-soft stage={} errorType={}", "parseDouble", "invalid_number");
            return defaultValue;
        }
    }

    static long parseLong(Object value, long defaultValue) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return n.longValue();
            }
            log.debug("[SelfAskNumbers] fail-soft stage={} errorType={}", "parseLong", "invalid_number");
            return defaultValue;
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            log.debug("[SelfAskNumbers] fail-soft stage={} errorType={}", "parseLong", "invalid_number");
            return defaultValue;
        }
    }

    static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
