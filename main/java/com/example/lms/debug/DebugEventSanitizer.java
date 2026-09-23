package com.example.lms.debug;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Best-effort sanitization for {@link DebugEvent} payloads.
 */
final class DebugEventSanitizer {

    private static final int MAX_STR = 2048;
    private static final int MAX_ITEMS = 80;
    private static final int MAX_DEPTH = 6;

    private DebugEventSanitizer() {
    }

    static Map<String, Object> sanitizeMap(Map<String, Object> in) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        return castToMap(sanitizeValue(null, in, 0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private static Object sanitizeValue(String key, Object v, int depth) {
        if (v == null) return null;
        if (depth > MAX_DEPTH) return "(depth-limit)";

        if (SafeRedactor.isRestrictedKey(key)) {
            return SafeRedactor.diagnosticValue(key, v, MAX_STR);
        }

        if (v instanceof String s) {
            return SafeRedactor.diagnosticValue(key, s, MAX_STR);
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }

        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ >= MAX_ITEMS) {
                    out.put("_truncated", true);
                    break;
                }
                if (e.getKey() == null) continue;
                String childKey = String.valueOf(e.getKey());
                out.put(safeKey(childKey), sanitizeValue(childKey, e.getValue(), depth + 1));
            }
            return out;
        }

        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (Object it : c) {
                if (i++ >= MAX_ITEMS) {
                    out.add("(truncated)");
                    break;
                }
                out.add(sanitizeValue(key, it, depth + 1));
            }
            return out;
        }

        if (v.getClass().isArray()) {
            try {
                int len = java.lang.reflect.Array.getLength(v);
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < Math.min(len, MAX_ITEMS); i++) {
                    out.add(sanitizeValue(key, java.lang.reflect.Array.get(v, i), depth + 1));
                }
                if (len > MAX_ITEMS) out.add("(truncated)");
                return out;
            } catch (Throwable ignore) {
                traceSuppressed("debugEventSanitizer.array", ignore);
                return "(" + v.getClass().getSimpleName() + ")";
            }
        }

        return SafeRedactor.diagnosticValue(key, String.valueOf(v), MAX_STR);
    }

    private static String safeKey(String key) {
        return SafeRedactor.traceLabelOrFallback(key, "field");
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = SafeRedactor.traceLabelOrFallback(
                ignored == null ? null : ignored.getClass().getSimpleName(),
                "unknown");
        TraceStore.put("debugEvent.sanitizer.suppressed.stage", safeStage);
        TraceStore.put("debugEvent.sanitizer.suppressed.errorType", errorType);
        TraceStore.put("debugEvent.sanitizer.suppressed." + safeStage, true);
        TraceStore.put("debugEvent.sanitizer.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_STR) return s;
        return s.substring(0, MAX_STR) + "...";
    }
}
