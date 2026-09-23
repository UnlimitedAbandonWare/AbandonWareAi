package com.example.lms.search;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-request structured event stream for narrowing down where a RAG/search
 * request deviated from its contract.
 *
 * <p>One line per event, stored in {@link TraceStore} under {@link #KEY} so the
 * caller and worker threads (which share the same context map through
 * ContextPropagation) write into a single ordered stream:</p>
 *
 * <pre>seq|t+&lt;ms&gt;|stage|event|k=v;k=v|remain=&lt;ms&gt;;bstate=&lt;ok|expired|cancelled|absent&gt;</pre>
 *
 * <ul>
 *   <li>{@code seq} — shared atomic counter, so cross-thread ordering is
 *       preserved even when worker and caller interleave.</li>
 *   <li>{@code t+ms} — elapsed since {@link #markRequestStart()} on the same
 *       monotonic clock ({@link System#nanoTime()}), never wall-clock.</li>
 *   <li>{@code remain}/{@code bstate} — the request-level TimeBudget observed
 *       at emit time. {@code bstate} distinguishes an absent context
 *       ({@code absent}, remain=-1) from natural expiry ({@code expired}) and
 *       cooperative cancellation ({@code cancelled}); it is never collapsed
 *       into a bare "0".</li>
 * </ul>
 *
 * <p>Safety contract: fail-soft (never throws, never blocks), bounded
 * ({@link #MAX_EVENTS} events, {@link #MAX_DOC_EVENTS} doc-detail lines),
 * and gated — when disabled, {@link #emit} returns before any string
 * assembly beyond the caller's own argument evaluation. Field values must
 * already be safe (counts, reason codes, hashes, enum/simple names); this
 * class strips only the structural separators {@code |} and newline.</p>
 */
public final class RequestTrace {

    /** TraceStore key holding the ordered List<String> of event lines. */
    public static final String KEY = "request.events";
    /** Count of events dropped after the buffer filled. */
    public static final String DROPPED_KEY = "request.events.dropped";
    /** Set true once any event was dropped — never claim completeness then. */
    public static final String INCOMPLETE_KEY = "request.events.incomplete";
    /** Per-request enable flag (propagates to workers via the shared map). */
    public static final String ENABLED_KEY = "request.trace.enabled";
    /** Per-request flag enabling per-document detail lines. */
    public static final String DOCS_KEY = "request.trace.docs";
    /** Server-generated request correlation id (UUID, non-secret). */
    public static final String ID_KEY = "request.id";

    private static final String SEQ_KEY = "request.events.seq";
    private static final String T0_KEY = "request.t0.nanos";
    private static final String DOC_EMITTED_KEY = "request.events.docs.emitted";
    /** Count of per-document lines dropped after the doc buffer filled. */
    public static final String DOC_DROPPED_KEY = "request.events.docs.dropped";

    private static final int MAX_EVENTS = 128;
    private static final int MAX_DOC_EVENTS = 32;
    private static final int MAX_FIELD_LEN = 240;

    /** Bound once from the existing rag.eval.debug-events.enabled property. */
    private static volatile boolean masterEnabled;

    private RequestTrace() {
    }

    public static void setMasterEnabled(boolean on) {
        masterEnabled = on;
    }

    /** True when this request's events should be recorded. */
    public static boolean enabled() {
        try {
            Object flag = TraceStore.get(ENABLED_KEY);
            if (flag instanceof Boolean b) {
                return b;
            }
        } catch (RuntimeException ignored) {
        }
        return masterEnabled;
    }

    /** True when bounded per-document detail lines should be recorded. */
    public static boolean docsEnabled() {
        try {
            return enabled() && Boolean.TRUE.equals(TraceStore.get(DOCS_KEY));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Record the monotonic request origin. Idempotent: the first caller wins,
     * so the controller endpoint and the orchestrator entry share one t0.
     */
    public static void markRequestStart() {
        try {
            TraceStore.context().putIfAbsent(T0_KEY, System.nanoTime());
        } catch (RuntimeException ignored) {
        }
    }

    public static void emit(String stage, String event) {
        emit(stage, event, "");
    }

    /**
     * Append one event. Callers on hot paths should check {@link #enabled()}
     * first when the {@code fields} string would otherwise be built anyway.
     */
    public static void emit(String stage, String event, String fields) {
        try {
            if (!enabled()) {
                return;
            }
            Map<String, Object> ctx = TraceStore.context();
            long t0 = origin(ctx);
            long seq = nextSeq(ctx);
            StringBuilder sb = new StringBuilder(96);
            sb.append(seq).append("|t+").append(elapsedMs(t0)).append('|')
                    .append(clean(stage)).append('|').append(clean(event)).append('|');
            if (fields != null && !fields.isBlank()) {
                sb.append(cleanFields(fields));
            }
            sb.append('|');
            appendBudget(sb);
            appendEvent(ctx, sb.toString());
        } catch (RuntimeException ignored) {
            // debug telemetry only — never break the request path
        }
    }

    /**
     * Bounded per-document detail line (deep-dive/tests only). Self-caps at
     * {@link #MAX_DOC_EVENTS}; overflow is counted, not silently dropped.
     */
    public static void emitDoc(String stage, String fields) {
        try {
            if (!docsEnabled()) {
                return;
            }
            Map<String, Object> ctx = TraceStore.context();
            Object counter = ctx.computeIfAbsent(DOC_EMITTED_KEY, k -> new AtomicLong(0));
            if (!(counter instanceof AtomicLong emitted)) {
                return;
            }
            if (emitted.incrementAndGet() > MAX_DOC_EVENTS) {
                TraceStore.inc(DOC_DROPPED_KEY);
                ctx.putIfAbsent(INCOMPLETE_KEY, Boolean.TRUE);
                return;
            }
            emit(stage, "doc", fields);
        } catch (RuntimeException ignored) {
        }
    }

    /** Ordered event lines recorded so far for this request; empty when none. */
    @SuppressWarnings("unchecked")
    public static List<String> events() {
        try {
            Object value = TraceStore.context().get(KEY);
            return value instanceof List<?> list ? (List<String>) list : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static long origin(Map<String, Object> ctx) {
        Object value = ctx.putIfAbsent(T0_KEY, System.nanoTime());
        return value instanceof Number n ? n.longValue() : System.nanoTime();
    }

    private static long nextSeq(Map<String, Object> ctx) {
        Object value = ctx.computeIfAbsent(SEQ_KEY, k -> new AtomicLong(0));
        return value instanceof AtomicLong seq ? seq.incrementAndGet() : 0L;
    }

    private static long elapsedMs(long t0) {
        return Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);
    }

    private static void appendBudget(StringBuilder sb) {
        TimeBudget budget;
        try {
            budget = TimeBudgetContext.get();
        } catch (RuntimeException e) {
            budget = null;
        }
        if (budget == null) {
            sb.append("remain=-1;bstate=absent");
            return;
        }
        long remain = budget.remainingMillis();
        sb.append("remain=").append(remain);
        sb.append(";bstate=")
                .append(budget.cancelled() ? "cancelled" : (remain <= 0L ? "expired" : "ok"));
    }

    @SuppressWarnings("unchecked")
    private static void appendEvent(Map<String, Object> ctx, String entry) {
        Object current = ctx.computeIfAbsent(KEY, k -> new CopyOnWriteArrayList<String>());
        if (current instanceof List<?> list && list.size() < MAX_EVENTS) {
            ((List<String>) list).add(entry);
            return;
        }
        Object dropped = ctx.computeIfAbsent(DROPPED_KEY, k -> new AtomicLong(0));
        if (dropped instanceof AtomicLong d) {
            d.incrementAndGet();
        }
        ctx.putIfAbsent(INCOMPLETE_KEY, Boolean.TRUE);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return "?";
        }
        return value.replace('|', '/').replace('\n', ' ').trim();
    }

    private static String cleanFields(String value) {
        String cleaned = value.replace('|', ';').replace('\n', ' ').trim();
        return cleaned.length() > MAX_FIELD_LEN ? cleaned.substring(0, MAX_FIELD_LEN) : cleaned;
    }
}
