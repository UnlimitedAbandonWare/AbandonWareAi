package com.example.lms.routing;

import java.util.Locale;
import java.util.Objects;

/**
 * Secret-safe routing/debug logger for API selection and failures.
 * Never pass raw API keys, Authorization headers, or secret values into these methods.
 */
public final class ApiRoutingDebug {

    private static final System.Logger LOG = System.getLogger(ApiRoutingDebug.class.getName());

    private ApiRoutingDebug() {
    }

    public static void decision(
            String purpose,
            String provider,
            String model,
            String endpointClass,
            boolean keyPresent,
            String keySource) {
        if (!LOG.isLoggable(System.Logger.Level.INFO)) {
            return;
        }
        LOG.log(
                System.Logger.Level.INFO,
                "[AWX][api-route] decision purpose={0} provider={1} model={2} endpointClass={3} keyPresent={4} keySource={5}",
                safe(purpose),
                safe(provider),
                safe(model),
                safe(endpointClass),
                keyPresent,
                safe(keySource));
    }

    public static void failure(
            String purpose,
            String provider,
            String model,
            String endpointClass,
            int attempt,
            Integer httpStatus,
            String errorClass,
            String fallbackTo) {
        if (!LOG.isLoggable(System.Logger.Level.WARNING)) {
            return;
        }
        LOG.log(
                System.Logger.Level.WARNING,
                "[AWX][api-route] failure purpose={0} provider={1} model={2} endpointClass={3} attempt={4} httpStatus={5} errorClass={6} fallbackTo={7}",
                safe(purpose),
                safe(provider),
                safe(model),
                safe(endpointClass),
                attempt,
                httpStatus == null ? "n/a" : httpStatus,
                classify(errorClass, httpStatus),
                safe(fallbackTo));
    }

    public static String classify(String errorClass, Integer httpStatus) {
        if (errorClass != null && !errorClass.isBlank()) {
            return errorClass.trim().toLowerCase(Locale.ROOT);
        }
        if (httpStatus == null) {
            return "unknown";
        }
        return switch (httpStatus) {
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 429 -> "rate_limited";
            case 408 -> "timeout";
            default -> httpStatus >= 500 ? "upstream_5xx" : "http_" + httpStatus;
        };
    }

    public static boolean keyPresent(String value) {
        if (value == null) {
            return false;
        }
        String t = value.trim();
        if (t.isEmpty()) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        return !(lower.startsWith("${")
                || lower.equals("changeme")
                || lower.equals("test")
                || lower.equals("dummy")
                || lower.equals("sk-local")
                || lower.equals("your_api_key_here"));
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        String t = value.trim();
        // Defense-in-depth: never echo long opaque tokens if a caller mistakes a key for a label.
        if (t.length() > 96) {
            return t.substring(0, 24) + "…len=" + t.length();
        }
        if (looksLikeSecret(t)) {
            return "redacted_len=" + t.length();
        }
        return t;
    }

    private static boolean looksLikeSecret(String t) {
        String lower = t.toLowerCase(Locale.ROOT);
        return lower.startsWith("sk-")
                || lower.startsWith("gsk_")
                || lower.startsWith("tvly-")
                || lower.startsWith("pcsk_")
                || lower.startsWith("snx_")
                || lower.startsWith("vck_")
                || lower.startsWith("bearer ");
    }
}
