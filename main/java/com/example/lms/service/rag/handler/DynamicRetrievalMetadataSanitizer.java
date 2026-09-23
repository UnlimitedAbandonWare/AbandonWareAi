package com.example.lms.service.rag.handler;

import com.example.lms.trace.SafeRedactor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class DynamicRetrievalMetadataSanitizer {

    private DynamicRetrievalMetadataSanitizer() {
    }

    static Map<String, Object> sanitize(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            copyEntry(entry.getKey(), entry.getValue(), out);
        }
        return out;
    }

    private static void copyEntry(Object rawKey, Object rawValue, Map<String, Object> target) {
        if (rawKey == null || rawValue == null || target == null) {
            return;
        }
        String key = String.valueOf(rawKey).trim();
        if (key.isBlank() || isRestrictedKey(key)) {
            return;
        }
        Object value = sanitizeValue(key, rawValue);
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Object sanitizeValue(String key, Object value) {
        if (value instanceof Number || value instanceof Boolean || value instanceof UUID) {
            return value;
        }
        if (value instanceof CharSequence seq) {
            return SafeRedactor.safeMessage(seq.toString(), 400);
        }
        return SafeRedactor.diagnosticValue(key, value, 400);
    }

    private static boolean isRestrictedKey(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
        if (k.equals("tokenbucket") || k.endsWith(".tokenbucket")) {
            return false;
        }
        return k.contains("authorization")
                || k.contains("apikey")
                || k.contains("servicerolekey")
                || k.contains("clientsecret")
                || k.contains("secret")
                || k.contains("password")
                || k.contains("cookie")
                || k.contains("ownertoken")
                || k.contains("rawquery")
                || k.endsWith("query")
                || k.contains(".query")
                || k.contains("prompt")
                || k.contains("snippet")
                || k.contains("content")
                || k.contains("rawtext")
                || k.contains("origtext")
                || k.contains("payload");
    }
}
