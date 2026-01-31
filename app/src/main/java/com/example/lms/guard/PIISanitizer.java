package com.example.lms.guard;

import java.util.regex.Pattern;

/**
 * Minimal PII masker for emails and phone numbers.
 */
public final class PIISanitizer {
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])([A-Za-z0-9._%+-]*)(@[^\\\\s]+)");
    private static final Pattern PHONE = Pattern.compile("(\\\\+?\\\\d{1,3}[\\\\s-]?)?(\\\\d{2,4}[\\\\s-]?){2,4}\\\\d{2,4}");

    public String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String masked = EMAIL.matcher(text).replaceAll("$1***$3");
        // Very conservative phone mask (replace digits with '*', keep separators)
        masked = masked.replaceAll("\\d(?=(?:.*\\d){3})", "*");
        return masked;
    }
}