package com.abandonware.ai.guard;

import java.util.regex.Pattern;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.guard.PIISanitizer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.guard.PIISanitizer
role: config
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