package com.example.lms.api;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

/**
 * Diagnostics API for {@link DebugEventStore}.
 *
 * <p>
 * Intent: make "silent" fail-soft paths observable by surfacing structured
 * events in-process.
 * </p>
 *
 * <p>
 * merge15-debug+: includes an SSE stream endpoint for "tail -f" style viewing.
 * </p>
 */
@RestController
@RequestMapping("/api/diagnostics/debug")
public class DebugEventsDiagnosticsController {

    private static final System.Logger LOG = System.getLogger(DebugEventsDiagnosticsController.class.getName());
    private static final long DEFAULT_SSE_TIMEOUT_MS = 300_000L;
    private static final long MIN_SSE_TIMEOUT_MS = 1_000L;
    private static final long MAX_SSE_TIMEOUT_MS = 3_600_000L;

    private final DebugEventStore store;
    @Autowired(required = false)
    private com.example.lms.debug.ApiFailureRecorder apiFailureRecorder;
    @Autowired(required = false)
    private com.example.lms.assist.ConversateApiCueService cueService;

    @GetMapping(value = "/api-failures", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> apiFailures() {
        return Map.of("incidents", apiFailureRecorder == null ? List.of() : apiFailureRecorder.snapshot(),
                "persistenceHealthy", apiFailureRecorder != null && apiFailureRecorder.persistenceHealthy(),
                "status", apiFailureRecorder == null ? Map.of("overall", "OK", "providers", List.of())
                        : apiFailureRecorder.statusSummary(),
                "routeHealth", cueService == null ? List.of() : cueService.routeHealth());
    }
    private final DebugEventsSseRuntime sseRuntime;
    private final long sseTimeoutMs;
    private final LongFunction<SseEmitter> emitterFactory;

    @Autowired
    public DebugEventsDiagnosticsController(
            DebugEventStore store,
            DebugEventsSseRuntime sseRuntime,
            @Value("${lms.debug.events.sse.timeout-ms:300000}") long configuredSseTimeoutMs) {
        this(store, sseRuntime, configuredSseTimeoutMs, SseEmitter::new);
    }

    DebugEventsDiagnosticsController(
            DebugEventStore store,
            DebugEventsSseRuntime sseRuntime,
            long configuredSseTimeoutMs,
            LongFunction<SseEmitter> emitterFactory) {
        this.store = Objects.requireNonNull(store, "store");
        this.sseRuntime = Objects.requireNonNull(sseRuntime, "sseRuntime");
        this.sseTimeoutMs = normalizeSseTimeout(configuredSseTimeoutMs);
        this.emitterFactory = Objects.requireNonNull(emitterFactory, "emitterFactory");
    }

    @GetMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<DebugEvent> list(@RequestParam(name = "limit", defaultValue = "80") int limit) {
        return store.list(limit);
    }

    @GetMapping(value = "/events/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public DebugEvent get(@PathVariable("id") String id) {
        return store.get(id);
    }

    @GetMapping(value = "/fingerprints", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> fingerprints(@RequestParam(name = "limit", defaultValue = "120") int limit) {
        return store.listFingerprints(limit);
    }

    /**
     * Live stream of debug events as Server-Sent Events (SSE).
     *
     * <p>
     * Endpoint:
     * </p>
     * <ul>
     * <li>GET /api/diagnostics/debug/events/stream?limit=50</li>
     * </ul>
     *
     * <p>
     * Best-effort "tail -f": sends an initial backlog (oldest → newest) then emits
     * new events as they appear.
     * </p>
     */
    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "pollMs", defaultValue = "900") long pollMs,
            @RequestParam(name = "heartbeatMs", defaultValue = "15000") long heartbeatMs,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        final int initialLimit = clamp(limit, 1, 500);
        final long sleepMs = clamp(pollMs, 200, 5000);
        final long hbMs = clamp(heartbeatMs, 3000, 60000);

        final SseEmitter emitter = emitterFactory.apply(sseTimeoutMs);
        final StreamSession session = new StreamSession(emitter);

        emitter.onCompletion(session::terminateFromCallback);
        emitter.onTimeout(session::terminateFromCallback);
        emitter.onError(failure -> session.terminateFromCallback());

        final FutureTask<Void> task = new FutureTask<>(() -> {
            // If reconnecting, best-effort resume from last id (if still in ring).
            try {
                if (lastEventId != null && !lastEventId.isBlank()) {
                    DebugEvent last = store.get(lastEventId.trim());
                    session.resumeFrom(last);
                }
            } catch (Throwable ignore) {
                traceSuppressed("stream.resume", ignore);
            }

            long lastHeartbeatAt = System.currentTimeMillis();

            try {
                // Hello / meta event
                try {
                    Map<String, Object> hello = new LinkedHashMap<>();
                    hello.put("ts", Instant.now().toString());
                    hello.put("mode", "sse");
                    hello.put("initialLimit", initialLimit);
                    hello.put("pollMs", sleepMs);
                    emitter.send(SseEmitter.event().name("hello").data(hello));
                } catch (IOException e) {
                    traceSuppressed("stream.hello", e);
                    session.stopFromWorker();
                }

                // Initial backlog (oldest -> newest)
                if (session.isOpen()) {
                    List<DebugEvent> initial = safeList(initialLimit);
                    Collections.reverse(initial);
                    session.sendEvents(initial, "stream.initial");
                }

                // Tail loop
                while (session.isOpen()) {
                    List<DebugEvent> snapshot = safeList(Math.max(initialLimit, 120));
                    session.sendEvents(session.newEvents(snapshot), "stream.tail");

                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeatAt >= hbMs) {
                        try {
                            emitter.send(SseEmitter.event().name("hb").comment("keep-alive"));
                        } catch (IOException e) {
                            traceSuppressed("stream.heartbeat", e);
                            session.stopFromWorker();
                            break;
                        }
                        lastHeartbeatAt = now;
                    }

                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        traceSuppressed("stream.sleep", ie);
                        Thread.currentThread().interrupt();
                        session.stopFromWorker();
                        break;
                    }
                }
            } finally {
                session.completeFromWorker();
            }
            return null;
        });

        if (!session.attach(task)) {
            return emitter;
        }
        try {
            sseRuntime.execute(task);
        } catch (RejectedExecutionException rejected) {
            session.rejectCapacity();
        }

        return emitter;
    }

    private List<DebugEvent> safeList(int lim) {
        try {
            return store.list(lim);
        } catch (Throwable ignore) {
            traceSuppressed("safeList", ignore);
            return List.of();
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Debug events diagnostics skipped stage={0} errorType={1}",
                    stage,
                    failure == null ? "unknown" : failure.getClass().getSimpleName());
        }
    }

    private static void sendEvent(SseEmitter emitter, DebugEvent ev) throws IOException {
        if (emitter == null || ev == null)
            return;
        emitter.send(SseEmitter.event()
                .name("debug-event")
                .id(ev.id())
                .data(ev));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long normalizeSseTimeout(long configuredTimeoutMs) {
        long positive = configuredTimeoutMs > 0L ? configuredTimeoutMs : DEFAULT_SSE_TIMEOUT_MS;
        return clamp(positive, MIN_SSE_TIMEOUT_MS, MAX_SSE_TIMEOUT_MS);
    }

    private static final class StreamSession {
        private final SseEmitter emitter;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean emitterTerminal = new AtomicBoolean(false);
        private final AtomicReference<FutureTask<Void>> taskRef = new AtomicReference<>();
        // Worker-confined progress belongs to this connection, alongside its task and emitter.
        private final Cursor cursor = new Cursor();

        private StreamSession(SseEmitter emitter) {
            this.emitter = Objects.requireNonNull(emitter, "emitter");
        }

        private void resumeFrom(DebugEvent last) {
            if (last != null) {
                cursor.lastTsMs = last.tsMs();
                cursor.idsAtLastTs.add(last.id());
            }
        }

        private List<DebugEvent> newEvents(List<DebugEvent> snapshot) {
            List<DebugEvent> events = new ArrayList<>();
            for (DebugEvent event : snapshot) {
                if (cursor.isNew(event)) {
                    events.add(event);
                }
            }
            Collections.reverse(events);
            return events;
        }

        private void sendEvents(List<DebugEvent> events, String stage) {
            for (DebugEvent event : events) {
                if (!isOpen()) {
                    break;
                }
                try {
                    sendEvent(emitter, event);
                    cursor.advance(event);
                } catch (IOException failure) {
                    traceSuppressed(stage, failure);
                    stopFromWorker();
                    break;
                }
            }
        }

        private boolean attach(FutureTask<Void> task) {
            Objects.requireNonNull(task, "task");
            if (!taskRef.compareAndSet(null, task)) {
                throw new IllegalStateException("debug_events_sse_task_already_attached");
            }
            if (!open.get()) {
                task.cancel(true);
                return false;
            }
            return true;
        }

        private boolean isOpen() {
            return open.get();
        }

        private void terminateFromCallback() {
            emitterTerminal.set(true);
            if (open.compareAndSet(true, false)) {
                cancelAttachedTask();
            }
        }

        private void stopFromWorker() {
            open.set(false);
        }

        private void completeFromWorker() {
            open.set(false);
            if (!emitterTerminal.compareAndSet(false, true)) {
                return;
            }
            try {
                emitter.complete();
            } catch (Throwable ex) {
                LOG.log(System.Logger.Level.DEBUG,
                        "Debug events SSE skipped stage=complete errorType=" + ex.getClass().getSimpleName());
            }
        }

        private void rejectCapacity() {
            if (open.compareAndSet(true, false)) {
                cancelAttachedTask();
            }
            if (!emitterTerminal.compareAndSet(false, true)) {
                return;
            }
            try {
                emitter.completeWithError(new IllegalStateException("debug_events_sse_capacity"));
            } catch (Throwable ex) {
                LOG.log(System.Logger.Level.DEBUG,
                        "Debug events SSE skipped stage=capacity errorType=" + ex.getClass().getSimpleName());
            }
        }

        private void cancelAttachedTask() {
            FutureTask<Void> task = taskRef.get();
            if (task != null) {
                task.cancel(true);
            }
        }
    }

    private static final class Cursor {
        long lastTsMs = 0L;
        final Set<String> idsAtLastTs = new HashSet<>();

        boolean isNew(DebugEvent ev) {
            if (ev == null)
                return false;
            long t = ev.tsMs();
            String id = ev.id();
            if (t > lastTsMs)
                return true;
            if (t < lastTsMs)
                return false;
            if (id == null)
                return false;
            return !idsAtLastTs.contains(id);
        }

        void advance(DebugEvent ev) {
            if (ev == null)
                return;
            long t = ev.tsMs();
            String id = ev.id();
            if (t > lastTsMs) {
                lastTsMs = t;
                idsAtLastTs.clear();
                if (id != null)
                    idsAtLastTs.add(id);
                return;
            }
            if (t == lastTsMs && id != null) {
                idsAtLastTs.add(id);
            }
        }
    }
}
