package com.nova.protocol.guard;

import java.util.regex.Pattern;



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