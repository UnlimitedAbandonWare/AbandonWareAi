package com.abandonwareai.zerobreak.sanitizer;

import java.util.regex.Pattern;

/** Simple, strict PII sanitizer for final answer text. */
public class PIISanitizer {
    private static final Pattern PHONE = Pattern.compile("(\\\\+?\\\\d[\\\\d\\\\-\\\\s]{7,}\\\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}");

    public String sanitize(String text) {
        if (text == null) return null;
        String t = PHONE.matcher(text).replaceAll("[전화번호 비공개]");
        t = EMAIL.matcher(t).replaceAll("[이메일 비공개]");
        return t;
    }
}