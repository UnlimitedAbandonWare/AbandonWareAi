package com.example.lms.guard;

import java.util.regex.Pattern;

/**
 * Simple PII sanitizer supporting email/phone patterns.
 */
public class PiiSanitizer {

    public enum Mode { redact, mask, block }

    private final boolean enabled;
    private final Mode mode;

    public PiiSanitizer() {
        this.enabled = Boolean.parseBoolean(System.getProperty("guard.pii.enabled", "true"));
        String m = System.getProperty("guard.pii.mode", "redact");
        this.mode = Mode.valueOf(m);
    }

    public String apply(String text) {
        if (!enabled || text == null) return text;
        String t = maskEmail(text);
        t = maskPhone(t);
        return t;
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(\\+?\\d{1,3}[ -]?)?(\\(?\\d{2,4}\\)?[ -]?)?[\\d -]{7,}");

    private String maskEmail(String s) {
        return EMAIL.matcher(s).replaceAll(mode==Mode.block ? "[blocked]" : "***@***");
    }
    private String maskPhone(String s) {
        return PHONE.matcher(s).replaceAll(mode==Mode.block ? "[blocked]" : "********");
    }
}