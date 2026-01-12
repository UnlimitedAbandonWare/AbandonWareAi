package com.example.lms.debug;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-memory store for structured {@link DebugEvent}.
 *
 * <p>
 * Features:
 * <ul>
 * <li>ring buffer for recent events (web diagnostics)</li>
 * <li>single-line JSON logging to console</li>
 * <li>fingerprint-based rate limiting / aggregation to prevent warning
 * floods</li>
 * </ul>
 * </p>
 */
@Component
public class DebugEventStore {

    private static final Logger JSON_LOG = LoggerFactory.getLogger("DEBUG_EVENT_JSON");
    private static final Logger LOG = LoggerFactory.getLogger(DebugEventStore.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final Deque<DebugEvent> ring = new ConcurrentLinkedDeque<>();
    private final Map<String, AggState> byFingerprint = new ConcurrentHashMap<>();

    @Value("${lms.debug.events.enabled:true}")
    private boolean enabled;

    @Value("${lms.debug.events.max-size:600}")
    private int maxSize;

    @Value("${lms.debug.events.rate.window-ms:60000}")
    private long windowMs;

    @Value("${lms.debug.events.rate.max-per-window:6}")
    private long maxPerWindow;

    @Value("${lms.debug.events.rate.flush-interval-ms:15000}")
    private long flushIntervalMs;


    public DebugEventStore() {
        // Ensure Java time types (Instant) are serializable in JSON logs.
        // Without this, DebugEvent JSON emission can silently fail and remove observability.
        try {
            mapper.findAndRegisterModules();
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    /**
     * Emit a debug event.
     */
    public void emit(DebugProbeType probe,
            DebugEventLevel level,
            String fingerprint,
            String message,
            Map<String, Object> data,
            Throwable error) {
        emit(probe, level, fingerprint, message, null, data, error);
    }

    /**
     * Emit a debug event with a {@code where} tag (component/method boundary).
     */
    public void emit(DebugProbeType probe,
            DebugEventLevel level,
            String fingerprint,
            String message,
            String where,
            Map<String, Object> data,
            Throwable error) {
        if (!enabled)
            return;
        DebugProbeType p = (probe == null) ? DebugProbeType.GENERIC : probe;
        String fp = (fingerprint == null || fingerprint.isBlank())
                ? defaultFingerprint(p, message, error)
                : fingerprint;

        long nowMs = System.currentTimeMillis();
        AggDecision decision = decide(fp, nowMs, message, level, error, data);
        if (decision.mode == AggMode.SUPPRESS) {
            return;
        }

        // For summary events (aggregation flush), replace message.
        String finalMessage = message;
        Map<String, Object> finalData = data;
        DebugEventLevel finalLevel = level;
        Throwable finalErr = error;
        if (decision.mode == AggMode.EMIT_SUMMARY) {
            finalLevel = DebugEventLevel.WARN;
            finalMessage = "[rate-limit] fingerprint='" + fp + "' suppressed=" + decision.agg.suppressedInWindow()
                    + " windowCount=" + decision.agg.windowCount();
            Map<String, Object> dd = new LinkedHashMap<>();
            dd.put("originalMessage", message);
            dd.put("lastLevel", String.valueOf(level));
            if (error != null) {
                dd.put("lastError", error.getClass().getName() + ":" + safeMsg(error));
            }
            if (data != null && !data.isEmpty()) {
                dd.put("sample", data);
            }
            dd.put("fingerprint", fp);
            dd.put("suppressedInWindow", decision.agg.suppressedInWindow());
            dd.put("windowCount", decision.agg.windowCount());
            finalData = dd;
            finalErr = null;
        }

        Map<String, Object> sanitized = DebugEventSanitizer.sanitizeMap(finalData);
        sanitized = enrich(p, sanitized);

        DebugEvent ev = new DebugEvent(
                randomId(),
                Instant.ofEpochMilli(nowMs),
                nowMs,
                finalLevel == null ? DebugEventLevel.INFO : finalLevel,
                p,
                fp,
                safeStr(finalMessage),
                correlationSid(),
                correlationTraceId(),
                correlationRequestId(),
                Thread.currentThread().getName(),
                safeStr(where),
                sanitized,
                toDebugError(finalErr),
                decision.agg);

        addToRing(ev);
        logJson(ev);
    }

    /**
     * Start a timed probe scope.
     */
    public ProbeScope probe(DebugProbeType probe, String fingerprint, String message, Map<String, Object> data) {
        if (!enabled)
            return ProbeScope.noop();
        return new ProbeScope(this, probe, fingerprint, message, data);
    }

    /**
     * List recent events (newest first).
     */
    public List<DebugEvent> list(int limit) {
        int lim = Math.max(1, Math.min(limit, maxSize));
        List<DebugEvent> out = new ArrayList<>(lim);
        int i = 0;
        for (DebugEvent ev : ring) {
            if (i++ >= lim)
                break;
            out.add(ev);
        }
        return out;
    }

    /**
     * Get a single event by id.
     */
    public DebugEvent get(String id) {
        if (id == null || id.isBlank())
            return null;
        for (DebugEvent ev : ring) {
            if (id.equals(ev.id()))
                return ev;
        }
        return null;
    }

    /**
     * List fingerprints (hotspots), sorted by recent windowCount descending.
     */
    public List<Map<String, Object>> listFingerprints(int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, AggState> e : byFingerprint.entrySet()) {
            AggState a = e.getValue();
            if (a == null)
                continue;
            AggSnapshot s = a.snapshot(now, windowMs);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fingerprint", e.getKey());
            m.put("windowCount", s.windowCount);
            m.put("suppressedInWindow", s.suppressed);
            m.put("windowAgeMs", s.windowAgeMs);
            m.put("total", s.total);
            m.put("totalSuppressed", s.totalSuppressed);
            m.put("lastMessage", s.lastMessage);
            m.put("lastError", s.lastError);
            out.add(m);
        }
        out.sort(Comparator
                .comparingLong((Map<String, Object> m) -> ((Number) m.getOrDefault("windowCount", 0)).longValue())
                .reversed());
        if (out.size() > lim) {
            return out.subList(0, lim);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Aggregation / rate limiting
    // ---------------------------------------------------------------------

    private enum AggMode {
        EMIT, EMIT_SUMMARY, SUPPRESS
    }

    private static final class AggDecision {
        final AggMode mode;
        final DebugAgg agg;

        AggDecision(AggMode mode, DebugAgg agg) {
            this.mode = mode;
            this.agg = agg;
        }
    }

    private static final class AggState {
        long windowStartMs;
        long windowCount;
        long suppressedInWindow;
        long lastEmitMs;
        long total;
        long totalSuppressed;

        String lastMessage;
        String lastError;

        synchronized AggDecision onEvent(long nowMs,
                long windowMs,
                long maxPerWindow,
                long flushIntervalMs,
                String message,
                DebugEventLevel level,
                Throwable error,
                Map<String, Object> data) {
            if (windowStartMs <= 0) {
                windowStartMs = nowMs;
                windowCount = 0;
                suppressedInWindow = 0;
                lastEmitMs = 0;
            }
            if (nowMs - windowStartMs >= windowMs) {
                // new window
                windowStartMs = nowMs;
                windowCount = 0;
                suppressedInWindow = 0;
                lastEmitMs = 0;
            }

            total++;
            windowCount++;

            // Keep the last sample for summaries.
            if (message != null)
                lastMessage = message;
            if (error != null)
                lastError = error.getClass().getName() + ":" + safeMsg(error);

            if (windowCount <= maxPerWindow) {
                lastEmitMs = nowMs;
                return new AggDecision(AggMode.EMIT,
                        new DebugAgg(windowMs, windowCount, suppressedInWindow, windowStartMs, nowMs));
            }

            suppressedInWindow++;
            totalSuppressed++;

            // Occasionally flush a summary to avoid "silent" suppression.
            boolean shouldFlush = (flushIntervalMs > 0) && (nowMs - lastEmitMs >= flushIntervalMs);
            if (shouldFlush) {
                lastEmitMs = nowMs;
                return new AggDecision(AggMode.EMIT_SUMMARY,
                        new DebugAgg(windowMs, windowCount, suppressedInWindow, windowStartMs, nowMs));
            }
            return new AggDecision(AggMode.SUPPRESS,
                    new DebugAgg(windowMs, windowCount, suppressedInWindow, windowStartMs, nowMs));
        }

        synchronized AggSnapshot snapshot(long nowMs, long windowMs) {
            long age = (windowStartMs <= 0) ? -1 : Math.max(0, nowMs - windowStartMs);
            return new AggSnapshot(windowCount, suppressedInWindow, age, total, totalSuppressed, lastMessage,
                    lastError);
        }
    }

    private static final class AggSnapshot {
        final long windowCount;
        final long suppressed;
        final long windowAgeMs;
        final long total;
        final long totalSuppressed;
        final String lastMessage;
        final String lastError;

        AggSnapshot(long windowCount, long suppressed, long windowAgeMs, long total, long totalSuppressed,
                String lastMessage, String lastError) {
            this.windowCount = windowCount;
            this.suppressed = suppressed;
            this.windowAgeMs = windowAgeMs;
            this.total = total;
            this.totalSuppressed = totalSuppressed;
            this.lastMessage = lastMessage;
            this.lastError = lastError;
        }
    }

    private AggDecision decide(String fingerprint,
            long nowMs,
            String message,
            DebugEventLevel level,
            Throwable error,
            Map<String, Object> data) {
        AggState st = byFingerprint.computeIfAbsent(fingerprint, fp -> new AggState());
        return st.onEvent(nowMs, windowMs, maxPerWindow, flushIntervalMs, message, level, error, data);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private void addToRing(DebugEvent ev) {
        ring.addFirst(ev);
        while (ring.size() > maxSize) {
            ring.pollLast();
        }
    }

    private void logJson(DebugEvent ev) {
        try {
            // Avoid relying on JSR-310 modules for JSONL logs.
            // (LOG_XAWA: Instant serialization failed → lost observability)
            JSON_LOG.info(mapper.writeValueAsString(asJsonLine(ev)));
        } catch (Exception e) {
            // Never break the request path.
            LOG.debug("Failed to serialize DebugEvent: {}", e.toString());
        }
    }

    /**
     * JSONL-friendly representation that avoids JavaTime module requirements.
     */
    private static Map<String, Object> asJsonLine(DebugEvent ev) {
        if (ev == null) {
            return Map.of();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Instant ts = ev.ts();
        out.put("ts", ts != null ? ts.toString() : null);
        out.put("probe", ev.probe() != null ? ev.probe().name() : null);
        out.put("level", ev.level() != null ? ev.level().name() : null);
        out.put("fingerprint", ev.fingerprint());
        out.put("message", ev.message());
        out.put("where", ev.where());
        out.put("sid", ev.sid());
        out.put("traceId", ev.traceId());
        out.put("requestId", ev.requestId());
        out.put("data", ev.data());
        out.put("error", ev.error());
        return out;
    }

    private static String randomId() {
        // lighter than UUID, still unique enough for in-memory buffer.
        long r1 = ThreadLocalRandom.current().nextLong();
        long r2 = System.nanoTime();
        return Long.toHexString(r1) + "-" + Long.toHexString(r2);
    }

    private static String safeStr(String s) {
        if (s == null)
            return null;
        return PromptMasker.mask(s);
    }

    private static String safeMsg(Throwable t) {
        if (t == null)
            return null;
        String m = t.getMessage();
        if (m == null)
            return "";
        return safeStr(m);
    }

    private static DebugError toDebugError(Throwable t) {
        if (t == null)
            return null;
        String type = t.getClass().getName();
        String msg = safeMsg(t);
        String stack = compactStack(t, 14);
        return new DebugError(type, msg, stack);
    }

    private static String compactStack(Throwable t, int maxFrames) {
        if (t == null)
            return null;
        try {
            StackTraceElement[] st = t.getStackTrace();
            if (st == null || st.length == 0)
                return null;
            StringBuilder sb = new StringBuilder();
            int n = Math.min(maxFrames, st.length);
            for (int i = 0; i < n; i++) {
                StackTraceElement el = st[i];
                sb.append(el.getClassName()).append("#").append(el.getMethodName())
                        .append(":").append(el.getLineNumber());
                if (i < n - 1)
                    sb.append(" <- ");
            }
            if (st.length > n)
                sb.append(" <- …");
            return sb.toString();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null)
            return null;
        for (String x : xs) {
            if (x != null && !x.isBlank())
                return x;
        }
        return null;
    }

    private static String correlationSid() {
        // Multiple MDC keys exist in the codebase; try all.
        String mdc = firstNonBlank(
                MDC.get("sid"),
                MDC.get("sessionId"),
                MDC.get("session_id"),
                MDC.get("x-session-id"));

        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }

        // Best-effort fallback: TraceStore may survive in context-aware executors
        // even when MDC is missing (reactor/netty boundaries).
        try {
            Object v = TraceStore.get("sid");
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isBlank()) {
                    return s;
                }
            }
        } catch (Throwable ignore) {
        }

        return null;
    }

    private static String correlationTraceId() {
        String mdc = firstNonBlank(
                MDC.get("traceId"),
                MDC.get("trace"),
                MDC.get("x-trace-id"),
                MDC.get("x-traceid"));

        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }

        try {
            Object v = TraceStore.get("trace.id");
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isBlank()) {
                    return s;
                }
            }
        } catch (Throwable ignore) {
        }

        return null;
    }

    private static String correlationRequestId() {
        String mdc = firstNonBlank(
                MDC.get("x-request-id"),
                MDC.get("requestId"),
                MDC.get("x-correlation-id"));

        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }

        // If there is no dedicated request id, use the trace id as a fallback.
        String traceId = correlationTraceId();
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }

        return null;
    }

    private Map<String, Object> enrich(DebugProbeType probe, Map<String, Object> data) {
        if (data == null)
            data = Map.of();
        // Probe-specific light enrichment, keep low cardinality.
        Map<String, Object> out = new LinkedHashMap<>(data);
        switch (probe) {
            case NAVER_SEARCH -> {
                out.putIfAbsent("provider", "naver");
            }
            case QUERY_TRANSFORMER -> {
                out.putIfAbsent("kind", "query_transformer");
            }
            case NIGHTMARE_BREAKER -> {
                out.putIfAbsent("kind", "nightmare_breaker");
            }
            case EMBEDDING -> {
                out.putIfAbsent("kind", "embedding");
            }
            case MODEL_GUARD -> {
                out.putIfAbsent("kind", "model_guard");
            }
            case PROMPT -> {
                out.putIfAbsent("kind", "prompt");
            }
            case ORCHESTRATION -> {
                out.putIfAbsent("kind", "orchestration");
            }
            default -> {
            }
        }
        // Context presence flags: helpful to detect propagation gaps.
        out.putIfAbsent("ctx.sid.present", correlationSid() != null);
        out.putIfAbsent("ctx.trace.present", correlationTraceId() != null);
        out.putIfAbsent("ctx.requestId.present", correlationRequestId() != null);
        return out;
    }

    private static String defaultFingerprint(DebugProbeType probe, String message, Throwable error) {
        String base = String.valueOf(probe) + "|" + String.valueOf(message);
        if (error != null)
            base += "|" + error.getClass().getName();
        // Keep deterministic but short.
        return Integer.toHexString(Objects.hash(base));
    }

    // ---------------------------------------------------------------------
    // Timed probe scope
    // ---------------------------------------------------------------------

    public static final class ProbeScope {
        private final DebugEventStore store;
        private final DebugProbeType probe;
        private final String fingerprint;
        private final String message;
        private final long startMs;
        private final Map<String, Object> base;
        private volatile boolean done;

        private ProbeScope(DebugEventStore store,
                DebugProbeType probe,
                String fingerprint,
                String message,
                Map<String, Object> base) {
            this.store = store;
            this.probe = probe;
            this.fingerprint = fingerprint;
            this.message = message;
            this.base = (base == null) ? Map.of() : base;
            this.startMs = System.currentTimeMillis();
        }

        public static ProbeScope noop() {
            return new ProbeScope(null, DebugProbeType.GENERIC, "noop", "noop", Map.of());
        }

        public void success(Map<String, Object> extra) {
            if (done)
                return;
            done = true;
            if (store == null)
                return;
            Map<String, Object> d = new LinkedHashMap<>(base);
            d.put("durationMs", Math.max(0, System.currentTimeMillis() - startMs));
            if (extra != null)
                d.putAll(extra);
            store.emit(probe, DebugEventLevel.INFO, fingerprint, message, d, null);
        }

        public void failure(Throwable t, Map<String, Object> extra) {
            if (done)
                return;
            done = true;
            if (store == null)
                return;
            Map<String, Object> d = new LinkedHashMap<>(base);
            d.put("durationMs", Math.max(0, System.currentTimeMillis() - startMs));
            if (extra != null)
                d.putAll(extra);
            store.emit(probe, DebugEventLevel.WARN, fingerprint, message, d, t);
        }
    }
}
