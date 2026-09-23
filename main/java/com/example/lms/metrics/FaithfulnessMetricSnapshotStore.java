package com.example.lms.metrics;

import com.example.lms.trace.SafeRedactor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Last-known scalar faithfulness metrics for read-only dashboards.
 */
public final class FaithfulnessMetricSnapshotStore {

    private static final List<String> ALLOWED_PREFIXES = List.of(
            "rag.eval.normalized.",
            "rag.answerQuality.",
            "rag.blackbox.",
            "harmony.score.",
            "soak.");
    private static final AtomicReference<Map<String, Object>> LAST = new AtomicReference<>(Map.of());

    private FaithfulnessMetricSnapshotStore() {
    }

    public static void put(String key, Object value) {
        if (!allowedKey(key)) {
            return;
        }
        Object sanitized = sanitize(value);
        LAST.updateAndGet(previous -> {
            Map<String, Object> next = new LinkedHashMap<>(previous);
            if (sanitized == null) {
                next.remove(key);
            } else {
                next.put(key, sanitized);
            }
            return Map.copyOf(next);
        });
    }

    public static Object get(String key) {
        return key == null ? null : LAST.get().get(key);
    }

    public static Map<String, Object> snapshot() {
        return new LinkedHashMap<>(LAST.get());
    }

    public static void clear() {
        LAST.set(Map.of());
    }

    private static boolean allowedKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        for (String prefix : ALLOWED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Object sanitize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            double number = n.doubleValue();
            if (!Double.isFinite(number)) {
                return null;
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return n.longValue();
            }
            return number;
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence text) {
            return SafeRedactor.traceLabelOrFallback(text, "unknown");
        }
        return null;
    }
}
