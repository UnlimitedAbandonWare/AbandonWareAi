package com.example.lms.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;



/**
 * Utility for displaying human-readable URLs.  When provided a raw URL
 * containing percent escapes this helper will decode the value and HTML
 * escape reserved characters.  Use {@code human()} when rendering links
 * in the UI to improve readability without altering the underlying href.
 */
public final class UrlDisplayUtil {

    private UrlDisplayUtil() {
        // static utility class; prevent instantiation
    }

    /**
     * Return a decoded and escaped representation of the given URL for display.
     * If decoding fails the original raw URL is escaped and returned.  A null
     * input returns an empty string.
     *
     * @param raw the raw URL string (may be encoded), may be null
     * @return a human-friendly representation safe for HTML display
     */
    public static String human(String raw) {
        if (raw == null) {
            return "";
        }
        try {
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            return htmlEscape(decoded);
        } catch (Exception e) {
            return htmlEscape(raw);
        }
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}