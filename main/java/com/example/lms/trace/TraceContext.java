package com.example.lms.trace;

import com.abandonware.ai.addons.budget.TimeBudget;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request-scoped trace context that keeps MDC correlation identifiers aligned.
 */
public class TraceContext implements AutoCloseable {

    private static final ThreadLocal<TraceContext> CURRENT =
            ThreadLocal.withInitial(TraceContext::rootContext);

    private final TraceContext previous;
    private final String prevSid;
    private final String prevSessionId;
    private final String prevTrace;
    private final String prevTraceId;
    private final String prevRequestId;
    private final String prevDbgSearch;
    private final String prevDbgSearchSrc;
    private final String prevDbgSearchBoostEngines;

    private TimeBudget timeBudget;
    private final Map<String, Object> flags = new HashMap<>();

    private TraceContext() {
        this.previous = null;
        this.prevSid = null;
        this.prevSessionId = null;
        this.prevTrace = null;
        this.prevTraceId = null;
        this.prevRequestId = null;
        this.prevDbgSearch = null;
        this.prevDbgSearchSrc = null;
        this.prevDbgSearchBoostEngines = null;
    }

    private TraceContext(String sid, String trace) {
        this.previous = CURRENT.get();
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

        String t = trace == null ? "" : trace.trim();
        if (t.isBlank()) {
            t = UUID.randomUUID().toString();
        }
        MDC.put("trace", t);
        MDC.put("traceId", t);
        MDC.put("x-request-id", t);
        CURRENT.set(this);
    }

    private static TraceContext rootContext() {
        return new TraceContext();
    }

    public static TraceContext current() {
        return CURRENT.get();
    }

    public static boolean isAttached() {
        return MDC.get("trace") != null || MDC.get("traceId") != null || CURRENT.get().previous != null;
    }

    public static TraceContext attach(String sid, String trace) {
        return new TraceContext(sid, trace);
    }

    public static void cleanupCurrentThread() {
        CURRENT.remove();
    }

    @Override
    public void close() {
        restore("sid", prevSid);
        restore("sessionId", prevSessionId);
        restore("trace", prevTrace);
        restore("traceId", prevTraceId);
        restore("x-request-id", prevRequestId);
        restore("dbgSearch", prevDbgSearch);
        restore("dbgSearchSrc", prevDbgSearchSrc);
        restore("dbgSearchBoostEngines", prevDbgSearchBoostEngines);
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static void restore(String key, String prev) {
        if (prev != null) {
            MDC.put(key, prev);
        } else {
            MDC.remove(key);
        }
    }

    public static Map<String, String> snapshotMdc() {
        Map<String, String> values = MDC.getCopyOfContextMap();
        return values == null ? new HashMap<>() : new HashMap<>(values);
    }

    public TraceContext startWithBudget(Duration budget) {
        if (budget != null && !budget.isZero() && !budget.isNegative()) {
            this.timeBudget = TimeBudget.untilNanoDeadline(System.nanoTime() + budget.toNanos());
        }
        return this;
    }

    public TraceContext tightenBudget(Duration budget) {
        if (budget != null && !budget.isZero() && !budget.isNegative()) {
            TimeBudget candidate = TimeBudget.untilNanoDeadline(System.nanoTime() + budget.toNanos());
            if (timeBudget == null || candidate.remainingMillis() < timeBudget.remainingMillis()) {
                timeBudget = candidate;
            }
        }
        return this;
    }

    public long remainingMillis() {
        return timeBudget == null ? Long.MAX_VALUE : timeBudget.remainingMillis();
    }

    /** The existing immutable budget can cross execution boundaries without being replenished. */
    public TimeBudget timeBudget() {
        return timeBudget;
    }

    public void setFlag(String key, Object val) {
        if (key == null || key.isBlank()) {
            return;
        }
        flags.put(key, val);
    }

    public Object getFlag(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return flags.get(key);
    }

    public void pushRecoveryRound(Object recoveryRound) {
        flags.put("recovery.round.last", recoveryRound);
        Object raw = flags.get("recovery.rounds");
        List<Object> rounds = raw instanceof List<?> existing
                ? new ArrayList<>(existing)
                : new ArrayList<>();
        rounds.add(recoveryRound);
        flags.put("recovery.rounds", rounds);
    }

    public static void budgetStart(String name, long ms) {
        // Compatibility hook for older call sites.
    }

    public static void budgetNote(String name, String note) {
        // Compatibility hook for older call sites.
    }
}
