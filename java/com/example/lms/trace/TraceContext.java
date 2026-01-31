package com.example.lms.trace;

import org.slf4j.MDC;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Attach/restore request correlation identifiers on MDC.
 *
 * <p>Compatibility: some components use {@code sid}/{@code trace} while others use
 * {@code sessionId}/{@code traceId}. This utility keeps both pairs in sync.</p>
 */
public class TraceContext implements AutoCloseable {

    private final String prevSid;
    private final String prevSessionId;
    private final String prevTrace;
    private final String prevTraceId;
    private final String prevRequestId;
    private final String prevDbgSearch;
    private final String prevDbgSearchSrc;
    private final String prevDbgSearchBoostEngines;

    private long deadlineNanos = -1L;
    private final Map<String, Object> flags = new HashMap<>();

    private TraceContext(String sid, String trace) {
        this.prevSid = MDC.get("sid");
        this.prevSessionId = MDC.get("sessionId");
        this.prevTrace = MDC.get("trace");
        this.prevTraceId = MDC.get("traceId");
        this.prevRequestId = MDC.get("x-request-id");
        this.prevDbgSearch = MDC.get("dbgSearch");
        this.prevDbgSearchSrc = MDC.get("dbgSearchSrc");
        this.prevDbgSearchBoostEngines = MDC.get("dbgSearchBoostEngines");

        if (sid != null && !sid.isBlank()) {
            MDC.put("sid", sid);
            MDC.put("sessionId", sid);
        }

        String t = (trace == null) ? "" : trace.trim();
        if (t.isBlank()) {
            t = UUID.randomUUID().toString();
        }
        MDC.put("trace", t);
        MDC.put("traceId", t);
        // Keep x-request-id aligned with traceId for cross-service correlation.
        MDC.put("x-request-id", t);
    }

    /**
     * Attach the given session and trace identifiers to the MDC.
     *
     * @param sid   session identifier (optional)
     * @param trace trace identifier (optional)
     */
    public static TraceContext attach(String sid, String trace) {
        return new TraceContext(sid, trace);
    }

    @Override
    public void close() {
        restore("sid", prevSid);
        restore("sessionId", prevSessionId);
        restore("trace", prevTrace);
        restore("traceId", prevTraceId);
        restore("x-request-id", prevRequestId);

        // Restore optional debug keys as well (thread-reuse safety).
        restore("dbgSearch", prevDbgSearch);
        restore("dbgSearchSrc", prevDbgSearchSrc);
        restore("dbgSearchBoostEngines", prevDbgSearchBoostEngines);
    }

    private static void restore(String key, String prev) {
        if (prev != null) {
            MDC.put(key, prev);
        } else {
            MDC.remove(key);
        }
    }

    /**
     * Best-effort snapshot of current MDC map (mutable copy).
     */
    public static Map<String, String> snapshotMdc() {
        Map<String, String> m = MDC.getCopyOfContextMap();
        return (m == null) ? new HashMap<>() : new HashMap<>(m);
    }

    // ----------------------------------------------------------------------
    // Optional time budget helpers (kept for backwards compatibility).
    // ----------------------------------------------------------------------

    public TraceContext startWithBudget(Duration budget) {
        if (budget != null && !budget.isZero() && !budget.isNegative()) {
            this.deadlineNanos = System.nanoTime() + budget.toNanos();
        }
        return this;
    }

    public long remainingMillis() {
        if (deadlineNanos <= 0L) return Long.MAX_VALUE;
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    public void setFlag(String key, Object val) {
        if (key == null || key.isBlank()) return;
        flags.put(key, val);
    }

    public Object getFlag(String key) {
        if (key == null || key.isBlank()) return null;
        return flags.get(key);
    }

    // Legacy no-op hooks (kept for compatibility)
    public static void budgetStart(String name, long ms) {
        // no-op
    }

    public static void budgetNote(String name, String note) {
        // no-op
    }
}
