package com.example.lms.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Small helper to keep request/session correlation in *single-line* logs.
 *
 * <p>
 * We intentionally include the correlation ids in the message body (not only
 * via logback pattern) so that grep-based diagnostics can preserve the linkage.
 */
public final class LogCorrelation {

    /**
     * MDC key for request correlation ID (used by BraveSearchService, TraceFilter,
     * etc.)
     */
    public static final String KEY_REQUEST_ID = "x-request-id";
    /** MDC key for session correlation ID */
    public static final String KEY_SESSION_ID = "sessionId";

    private static final String BOOT_RID = "boot-" + UUID.randomUUID().toString().substring(0, 8);

    private LogCorrelation() {
    }

    /**
     * Best-effort request id.
     * <p>
     * In HTTP requests this should be populated by {@code TraceFilter}.
     * During boot we fall back to a stable-ish boot id.
     */
    public static String requestId() {
        String v = firstNonBlank(
                MDC.get("x-request-id"),
                MDC.get("trace"),
                MDC.get("rid"));
        return (v == null || v.isBlank()) ? BOOT_RID : v;
    }

    /**
     * Best-effort session id.
     * <p>
     * During HTTP requests this is usually bound by {@code GuardContextInitFilter}
     * (or {@code TraceFilter} via X-Session-Id).
     */
    public static String sessionId() {
        String v = firstNonBlank(
                MDC.get("sessionId"),
                MDC.get("sid"));
        return (v == null || v.isBlank()) ? "boot" : v;
    }

    /**
     * Common suffix for grep-friendly one-liners.
     */
    public static String suffix() {
        return " rid=" + requestId() + " sessionId=" + sessionId();
    }

    private static String firstNonBlank(String... vs) {
        if (vs == null) {
            return null;
        }
        for (String v : vs) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
