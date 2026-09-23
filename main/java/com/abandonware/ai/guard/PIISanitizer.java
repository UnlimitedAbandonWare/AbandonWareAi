package com.abandonware.ai.guard;

import java.util.regex.Pattern;

/**
 * Minimal PII sanitizer (email/phone) with safe Java string escaping.
 * Kept dependency-free to avoid compile errors in environments without Spring.
 */
public class PIISanitizer {

    private static final Pattern EMAIL = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\\\.[A-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\\\b(?:\\\\+?\\\\d[\\\\d\\\\- ]{8,})\\\\b");

    /** Null-safe sanitize. */
    public String sanitize(String input) {
        if (input == null) return null;
        String out = EMAIL.matcher(input).replaceAll("[email-redacted]");
        out = PHONE.matcher(out).replaceAll("[phone-redacted]");
        return out;
    }
}