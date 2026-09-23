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
    private static final System.Logger LOG = System.getLogger(UrlDisplayUtil.class.getName());
    private static final String SUPABASE_KEY_PATTERN = "(?i)\\bsb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}\\b";

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
            return htmlEscape(redactSensitiveQueryValues(decoded));
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "URL display decode failed rawLength={0} errorType={1}",
                    raw.length(), errorType(e));
            return htmlEscape(redactSensitiveQueryValues(raw));
        }
    }

    private static String redactSensitiveQueryValues(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.replaceAll("(?i)([?&][^=&#]*(?:token|api[_-]?key|secret|password)[^=&#]*=)[^&#]*", "$1[redacted]")
                .replaceAll(SUPABASE_KEY_PATTERN, "[redacted]");
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String errorType(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        if (error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return error.getClass().getSimpleName();
    }
}
