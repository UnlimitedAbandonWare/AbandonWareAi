package com.nova.protocol.guard;

import java.util.regex.Pattern;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.guard.PIISanitizer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.guard.PIISanitizer
role: config
*/
public class PIISanitizer {

    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\.[A-Za-z]{2,6}");
    private static final Pattern PHONE = Pattern.compile("\\\\b(?:\\\\+?\\\\d{1,3}[ -]?)?(?:\\\\d{2,4}[ -]?){2,4}\\\\d{4}\\\\b");

    public String scrub(String text) {
        if (text == null) return null;
        String t = EMAIL.matcher(text).replaceAll("[redacted-email]");
        t = PHONE.matcher(t).replaceAll("[redacted-phone]");
        return t;
    }
}