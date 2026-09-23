package com.example.lms.config;

import java.util.Locale;
import java.util.Set;

/**
 * Small helpers to interpret configuration values safely.
 *
 * <p>This project frequently uses placeholders with defaults to avoid
 * {@code Could not resolve placeholder} at boot time. For security-sensitive
 * keys/tokens we must still treat "missing" or "blank" values as *disabled*
 * rather than attempting external calls with an empty token.
 */
public final class ConfigValueGuards {

    /**
     * Sentinel used in YAML placeholders when we want boot to survive but want
     * to be able to detect "missing" reliably.
     */
    public static final String MISSING_SENTINEL = "__MISSING__";

    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "dummy",
            "dummy-key",
            "null",
            "test",
            "changeme",
            "change-me",
            "none",
            "n/a",
            "na",
            "todo",
            "tbd",
            "placeholder",
            "<your_api_key>",
            "<your-api-key>",
            "ollama",
            "sk-local"
    );

    private ConfigValueGuards() {
    }

    public static boolean isMissing(String v) {
        if (v == null) return true;
        String s = v.trim();
        if (s.isEmpty()) return true;
        if (MISSING_SENTINEL.equalsIgnoreCase(s)) return true;
        if (s.contains("${") && s.contains("}")) return true;
        String lower = s.toLowerCase(Locale.ROOT);
        // Common accidental values from templates.
        if (PLACEHOLDER_VALUES.contains(lower)) return true;
        if (lower.startsWith("change_me")) return true;
        if (lower.startsWith("<") && lower.endsWith(">")) return true;
        if (lower.startsWith("sk-local")) return true;
        return false;
    }

    public static boolean isMissingLocalOpenAiCompatKey(String v) {
        if (v == null) return true;
        String s = v.trim();
        if (s.isEmpty()) return true;
        if ("ollama".equalsIgnoreCase(s)) return false;
        if (MISSING_SENTINEL.equalsIgnoreCase(s)) return true;
        if (s.contains("${") && s.contains("}")) return true;
        String lower = s.toLowerCase(Locale.ROOT);
        if (PLACEHOLDER_VALUES.contains(lower)) return true;
        if (lower.startsWith("change_me")) return true;
        if (lower.startsWith("<") && lower.endsWith(">")) return true;
        if (lower.startsWith("sk-local")) return true;
        return false;
    }
}
