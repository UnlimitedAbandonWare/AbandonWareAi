package com.example.lms.config;

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

    private ConfigValueGuards() {
    }

    public static boolean isMissing(String v) {
        if (v == null) return true;
        String s = v.trim();
        if (s.isEmpty()) return true;
        if (MISSING_SENTINEL.equalsIgnoreCase(s)) return true;
        // Common accidental values from templates.
        if ("<YOUR_API_KEY>".equalsIgnoreCase(s)) return true;
        return false;
    }
}
