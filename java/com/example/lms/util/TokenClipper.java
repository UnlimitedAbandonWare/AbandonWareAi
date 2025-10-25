package com.example.lms.util;


/**
 * Utility for clipping long strings by whitespace token count.  A simple
 * approximation is used: input text is split on one or more whitespace
 * characters and truncated to the requested number of tokens.  This helper
 * avoids the need to depend on external tokenisation libraries while still
 * providing coarse length control on untrusted inputs such as uploaded
 * documents and prompt contexts.  When {@code text} is null the method
 * returns null.  If {@code maxTokens} is negative or zero the original
 * string is returned unchanged.
 */
public final class TokenClipper {

    private TokenClipper() {
        // Prevent instantiation
    }

    /**
     * Clip the supplied text to at most {@code maxTokens} whitespace‑separated
     * tokens.  If the text contains fewer tokens than the limit it is
     * returned unchanged.  When {@code text} is null this returns null.
     *
     * @param text      the input string (may be null)
     * @param maxTokens maximum number of tokens to retain
     * @return the clipped string or the original when no clipping is needed
     */
    public static String clip(String text, int maxTokens) {
        if (text == null) return null;
        if (maxTokens <= 0) return text;
        String[] tokens = text.split("\\s+");
        if (tokens.length > maxTokens) {
            return String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, maxTokens));
        }
        return text;
    }
}