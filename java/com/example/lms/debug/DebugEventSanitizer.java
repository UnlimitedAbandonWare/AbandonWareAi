package com.example.lms.debug;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Best-effort sanitization for {@link DebugEvent} payloads.
 *
 * <p>
 * The goal is to:
 * <ul>
 *   <li>avoid leaking secrets (api keys, bearer tokens)</li>
 *   <li>avoid flooding logs/endpoints with huge payloads</li>
 * </ul>
 * </p>
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
        return castToMap(sanitizeValue(in, 0));
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

    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(Object v, int depth) {
        if (v == null) return null;
        if (depth > MAX_DEPTH) return "(depth-limit)";

        if (v instanceof String s) {
            return truncate(PromptMasker.mask(s));
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
                String k = String.valueOf(e.getKey());
                if (isSensitiveKey(k)) {
                    out.put(k, "(redacted)");
                } else {
                    out.put(k, sanitizeValue(e.getValue(), depth + 1));
                }
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
                out.add(sanitizeValue(it, depth + 1));
            }
            return out;
        }

        if (v.getClass().isArray()) {
            // best-effort: treat as list
            try {
                int len = java.lang.reflect.Array.getLength(v);
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < Math.min(len, MAX_ITEMS); i++) {
                    out.add(sanitizeValue(java.lang.reflect.Array.get(v, i), depth + 1));
                }
                if (len > MAX_ITEMS) out.add("(truncated)");
                return out;
            } catch (Throwable ignore) {
                return "(" + v.getClass().getSimpleName() + ")";
            }
        }

        // fallback
        return truncate(PromptMasker.mask(String.valueOf(v)));
    }

    private static String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_STR) return s;
        return s.substring(0, MAX_STR) + "…";
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("authorization")
                || k.contains("api-key")
                || k.contains("apikey")
                || k.contains("secret")
                || k.contains("token")
                || k.contains("password")
                || k.contains("x-naver-client-secret")
                || k.contains("openai") && k.contains("key");
    }
}
