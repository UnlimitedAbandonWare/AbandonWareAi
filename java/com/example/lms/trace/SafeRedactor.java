// src/main/java/com/example/lms/trace/SafeRedactor.java
package com.example.lms.trace;

import com.example.lms.debug.PromptMasker;



/**
 * Thin wrapper around {@link PromptMasker} to centralise secret redaction
 * for trace logging.  All strings passed through this helper will have
 * API keys, bearer tokens, and other patterns removed before being
 * written to logs.  Additional redaction rules can be added here in
 * future without touching callers.
 */
public final class SafeRedactor {
    private SafeRedactor() {}

    /**
     * Redact sensitive information from the given string.  When the input
     * is {@code null}, {@code null} is returned.
     *
     * @param s text to redact
     * @return the redacted text
     */
    public static String redact(String s) {
        if (s == null) return null;
        return PromptMasker.mask(s);
    }
}