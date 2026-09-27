package com.example.lms.debug;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p>
 * Events are sanitized before JSON logging, but call sites should still prefer
 * hashes, counts, short reason codes, and provider names over raw queries or
 * secret-bearing values.
 * </p>
 */
@Component
public class DebugEventStore {

    private static final Logger JSON_LOG = LoggerFactory.getLogger("DEBUG_EVENT_JSON");
    private static final Logger LOG = LoggerFactory.getLogger(DebugEventStore.class);
    private static final int DEFAULT_NDJSON_QUEUE_CAPACITY = 256;
    private static final int MAX_NDJSON_QUEUE_CAPACITY = 8_192;
    private static final long NDJSON_KEEP_ALIVE_SECONDS = 5L;
    private static final long NDJSON_SHUTDOWN_WAIT_SECONDS = 1L;
    private static final AtomicInteger NDJSON_THREAD_SEQUENCE = new AtomicInteger();

    private final ObjectMapper mapper = new ObjectMapper();

    private final Deque<DebugEvent> ring = new ConcurrentLinkedDeque<>();
    private final Map<String, AggState> byFingerprint = new ConcurrentHashMap<>();
    private final Object aggregateStateMutex = new Object();
    private final Object ndjsonExecutorMutex = new Object();
    private final NdjsonLineWriter ndjsonLineWriter;
    private final ThreadFactory ndjsonThreadFactory;
    private final AtomicLong ndjsonDropped = new AtomicLong();
    private long aggregateTouchOrder;
    private volatile ThreadPoolExecutor ndjsonExecutor;
    private volatile boolean ndjsonWriterClosed;

    @Value("${lms.debug.events.enabled:true}")
    private boolean enabled = true;

    @Value("${lms.debug.events.max-size:600}")
    private int maxSize = 600;

    @Value("${lms.debug.events.rate.max-fingerprints:0}")
    private int maxFingerprints;

    @Value("${lms.debug.events.rate.window-ms:60000}")
    private long windowMs = 60_000L;

    @Value("${lms.debug.events.rate.max-per-window:6}")
    private long maxPerWindow = 6L;

    @Value("${lms.debug.events.rate.flush-interval-ms:15000}")
    private long flushIntervalMs = 15_000L;

    @Value("${abandonware.debug.ndjson-dir:${ABNADON_DEBUG_DIR:var/abnadon/debug}}")
    private String ndjsonDir = "var/abnadon/debug";

    @Value("${abandonware.debug.ndjson.enabled:true}")
    private boolean ndjsonEnabled = true;

    @Value("${abandonware.debug.ndjson.queue-capacity:256}")
    private int ndjsonQueueCapacity = DEFAULT_NDJSON_QUEUE_CAPACITY;

    public DebugEventStore() {
        this(DEFAULT_NDJSON_QUEUE_CAPACITY, DebugEventStore::writeNdjsonLine, daemonNdjsonThreadFactory());
    }

    DebugEventStore(int configuredQueueCapacity, NdjsonLineWriter ndjsonLineWriter, ThreadFactory threadFactory) {
        this.ndjsonQueueCapacity = normalizeNdjsonQueueCapacity(configuredQueueCapacity);
        this.ndjsonLineWriter = Objects.requireNonNull(ndjsonLineWriter, "ndjsonLineWriter");
        this.ndjsonThreadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
        // Ensure Java time types (Instant) are serializable in JSON logs.
        // Without this, DebugEvent JSON emission can silently fail and remove observability.
        try {
            mapper.findAndRegisterModules();
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        } catch (Throwable ignore) {
            traceSuppressed("debugEventStore.objectMapper", ignore);
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
        String selectedFingerprint = (fingerprint == null || fingerprint.isBlank())
                ? defaultFingerprint(p, message, error)
                : fingerprint;
        String fp = SafeRedactor.hashValue(selectedFingerprint);

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
            dd.put("originalMessage", safeStr(message));
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
                SafeRedactor.hashValue(correlationSid()),
                SafeRedactor.hashValue(correlationTraceId()),
                SafeRedactor.hashValue(correlationRequestId()),
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

    /** List recent events for one probe type, newest first. */
    public List<DebugEvent> listByProbe(DebugProbeType probe, int limit) {
        if (probe == null) {
            return List.of();
        }
        int lim = Math.max(1, Math.min(limit, maxSize));
        List<DebugEvent> out = new ArrayList<>(lim);
        for (DebugEvent ev : ring) {
            if (out.size() >= lim) {
                break;
            }
            if (ev != null && ev.probe() == probe) {
                out.add(ev);
            }
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
        synchronized (aggregateStateMutex) {
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
                m.put("lastMessage", safeStr(s.lastMessage));
                m.put("lastError", safeStr(s.lastError));
                out.add(m);
            }
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
        long lastTouchedOrder;

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
                lastMessage = safeStr(message);
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
        synchronized (aggregateStateMutex) {
            int capacity = effectiveMaxFingerprints();
            AggState st = byFingerprint.get(fingerprint);
            if (st == null) {
                evictFingerprintStatesForAdmission(capacity);
                st = new AggState();
                byFingerprint.put(fingerprint, st);
            }
            st.lastTouchedOrder = nextAggregateTouchOrder();
            trimFingerprintStatesToCapacity(capacity, fingerprint);
            return st.onEvent(nowMs, windowMs, maxPerWindow, flushIntervalMs, message, level, error, data);
        }
    }

    @PostConstruct
    void validateConfiguration() {
        if (maxSize <= 0) {
            throw new IllegalStateException("debug_event_capacity_invalid");
        }
    }

    private int effectiveMaxFingerprints() {
        return maxFingerprints > 0 ? maxFingerprints : Math.max(1, maxSize);
    }

    private void evictFingerprintStatesForAdmission(int capacity) {
        while (byFingerprint.size() >= capacity) {
            Map.Entry<String, AggState> victim = leastRecentlyTouchedState(null);
            byFingerprint.remove(victim.getKey(), victim.getValue());
        }
    }

    private void trimFingerprintStatesToCapacity(int capacity, String protectedFingerprint) {
        while (byFingerprint.size() > capacity) {
            Map.Entry<String, AggState> victim = leastRecentlyTouchedState(protectedFingerprint);
            byFingerprint.remove(victim.getKey(), victim.getValue());
        }
    }

    private Map.Entry<String, AggState> leastRecentlyTouchedState(String protectedFingerprint) {
        Map.Entry<String, AggState> victim = null;
        for (Map.Entry<String, AggState> candidate : byFingerprint.entrySet()) {
            if (Objects.equals(candidate.getKey(), protectedFingerprint)) {
                continue;
            }
            if (victim == null
                    || candidate.getValue().lastTouchedOrder < victim.getValue().lastTouchedOrder
                    || (candidate.getValue().lastTouchedOrder == victim.getValue().lastTouchedOrder
                            && candidate.getKey().compareTo(victim.getKey()) < 0)) {
                victim = candidate;
            }
        }
        return Objects.requireNonNull(victim, "fingerprint aggregate eviction candidate");
    }

    private long nextAggregateTouchOrder() {
        if (aggregateTouchOrder == Long.MAX_VALUE) {
            List<Map.Entry<String, AggState>> states = new ArrayList<>(byFingerprint.entrySet());
            states.sort(Comparator
                    .comparingLong((Map.Entry<String, AggState> e) -> e.getValue().lastTouchedOrder)
                    .thenComparing(Map.Entry::getKey));
            long next = 0L;
            for (Map.Entry<String, AggState> state : states) {
                state.getValue().lastTouchedOrder = ++next;
            }
            aggregateTouchOrder = next;
        }
        return ++aggregateTouchOrder;
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
            // Avoid relying on JSR-310 modules for JSONL logs; Instant is
            // converted explicitly so debug serialization cannot drop events.
            String jsonLine = mapper.writeValueAsString(asJsonLine(ev));
            JSON_LOG.info(jsonLine);
            mirrorNdjson(jsonLine);
        } catch (Exception e) {
            // Never break the request path.
            LOG.debug("Failed to serialize DebugEvent. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    private void mirrorNdjson(String jsonLine) {
        if (!ndjsonEnabled || jsonLine == null || jsonLine.isBlank()) {
            return;
        }

        String directory = ndjsonDir == null || ndjsonDir.isBlank() ? "var/abnadon/debug" : ndjsonDir.trim();
        String fileName = LocalDate.now().toString() + ".ndjson";
        ThreadPoolExecutor executor = ndjsonExecutor();
        if (executor == null) {
            recordNdjsonDrop("writer_closed");
            return;
        }

        try {
            executor.execute(() -> writeNdjson(directory, fileName, jsonLine));
        } catch (RejectedExecutionException rejected) {
            recordNdjsonDrop(executor.isShutdown() ? "writer_closed" : "queue_saturated");
        }
    }

    private void writeNdjson(String directory, String fileName, String jsonLine) {
        try {
            ndjsonLineWriter.write(directory, fileName, jsonLine);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.debug("Failed to mirror DebugEvent NDJSON. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(interrupted)), messageLength(interrupted));
        } catch (Exception e) {
            LOG.debug("Failed to mirror DebugEvent NDJSON. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    private ThreadPoolExecutor ndjsonExecutor() {
        ThreadPoolExecutor current = ndjsonExecutor;
        if (current != null) {
            return current;
        }
        synchronized (ndjsonExecutorMutex) {
            if (ndjsonWriterClosed) {
                return null;
            }
            current = ndjsonExecutor;
            if (current == null) {
                current = new ThreadPoolExecutor(
                        0,
                        1,
                        NDJSON_KEEP_ALIVE_SECONDS,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(normalizeNdjsonQueueCapacity(ndjsonQueueCapacity)),
                        ndjsonThreadFactory,
                        new ThreadPoolExecutor.AbortPolicy());
                ndjsonExecutor = current;
            }
            return current;
        }
    }

    private void recordNdjsonDrop(String reason) {
        long dropped = ndjsonDropped.incrementAndGet();
        if (dropped == 1L || (dropped & (dropped - 1L)) == 0L) {
            LOG.warn("DebugEvent NDJSON line dropped reason={} droppedTotal={} queueCapacity={}",
                    reason, dropped, normalizeNdjsonQueueCapacity(ndjsonQueueCapacity));
        }
    }

    @PreDestroy
    void shutdownNdjsonWriter() {
        ThreadPoolExecutor current;
        synchronized (ndjsonExecutorMutex) {
            ndjsonWriterClosed = true;
            current = ndjsonExecutor;
        }
        if (current == null) {
            return;
        }

        current.shutdown();
        try {
            if (!current.awaitTermination(NDJSON_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                current.shutdownNow();
                current.awaitTermination(NDJSON_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    int ndjsonQueueSize() {
        ThreadPoolExecutor current = ndjsonExecutor;
        return current == null ? 0 : current.getQueue().size();
    }

    long ndjsonDroppedCount() {
        return ndjsonDropped.get();
    }

    boolean awaitNdjsonWriterTermination(long timeout, TimeUnit unit) throws InterruptedException {
        ThreadPoolExecutor current = ndjsonExecutor;
        return current == null || current.awaitTermination(timeout, Objects.requireNonNull(unit, "unit"));
    }

    private static int normalizeNdjsonQueueCapacity(int configuredCapacity) {
        int positive = configuredCapacity > 0 ? configuredCapacity : DEFAULT_NDJSON_QUEUE_CAPACITY;
        return Math.min(positive, MAX_NDJSON_QUEUE_CAPACITY);
    }

    private static ThreadFactory daemonNdjsonThreadFactory() {
        return task -> {
            Thread thread = new Thread(task, "debug-event-ndjson-" + NDJSON_THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void writeNdjsonLine(String directory, String fileName, String jsonLine) throws Exception {
        Path base = Path.of(directory);
        Files.createDirectories(base);
        Path file = base.resolve(fileName);
        Files.writeString(file, jsonLine + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @FunctionalInterface
    interface NdjsonLineWriter {
        void write(String directory, String fileName, String jsonLine) throws Exception;
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
        return SafeRedactor.safeMessage(s, 2048);
    }

    private static String safeMsg(Throwable t) {
        if (t == null)
            return null;
        String m = t.getMessage();
        if (m == null)
            return "";
        return String.valueOf(SafeRedactor.diagnosticValue("error", m, 1024));
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
                sb.append(" <- ...");
            return sb.toString();
        } catch (Throwable ignore) {
            traceSuppressed("debugEventStore.stackTrace", ignore);
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
        } catch (Throwable traceError) {
            traceSuppressed("debugEventStore.traceContext.sid", traceError);
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
        } catch (Throwable traceError) {
            traceSuppressed("debugEventStore.traceContext.traceId", traceError);
        }

        return null;
    }

    private static void traceSuppressed(String stage, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = SafeRedactor.traceLabelOrFallback(
                error == null ? null : error.getClass().getSimpleName(),
                "unknown");
        try {
            TraceStore.put("debugEvent.store.suppressed.stage", safeStage);
            TraceStore.put("debugEvent.store.suppressed.errorType", errorType);
            TraceStore.put("debugEvent.store.suppressed." + safeStage, true);
            TraceStore.put("debugEvent.store.suppressed." + safeStage + ".errorType", errorType);
            TraceStore.inc("debugEvent.store.suppressed.count");
            TraceStore.inc("debugEvent.store.suppressed." + safeStage + ".count");
        } catch (Throwable traceError) {
            traceContextFallbackSkipped("debugEventStore.traceSuppressed", traceError);
        }
        LOG.debug("DebugEvent suppressed stage={} errorHash={} errorLength={}",
                safeStage,
                SafeRedactor.hashValue(messageOf(error)),
                messageLength(error));
    }

    private static void traceContextFallbackSkipped(String stage, Throwable error) {
        LOG.debug("DebugEvent context fallback skipped stage={} errorHash={} errorLength={}",
                stage,
                SafeRedactor.hashValue(messageOf(error)),
                messageLength(error));
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
            case IMAGE_JOB -> {
                out.putIfAbsent("kind", "image_job");
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
            case EXTERNAL_EVIDENCE -> {
                out.putIfAbsent("kind", "external_evidence");
            }
            case TRACE_MEMORY -> {
                out.putIfAbsent("kind", "trace_memory");
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

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
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
        private final AtomicBoolean done = new AtomicBoolean();

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
            if (!done.compareAndSet(false, true))
                return;
            if (store == null)
                return;
            Map<String, Object> d = new LinkedHashMap<>(base);
            d.put("durationMs", Math.max(0, System.currentTimeMillis() - startMs));
            if (extra != null)
                d.putAll(extra);
            store.emit(probe, DebugEventLevel.INFO, fingerprint, message, d, null);
        }

        public void failure(Throwable t, Map<String, Object> extra) {
            if (!done.compareAndSet(false, true))
                return;
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
