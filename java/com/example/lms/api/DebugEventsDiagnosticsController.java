package com.example.lms.api;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final DebugEventStore store;

    // Lightweight shared pool for SSE streams. Expected usage: small (ops-only).
    private static final ExecutorService SSE_EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "debug-events-sse");
        t.setDaemon(true);
        return t;
    });

    public DebugEventsDiagnosticsController(DebugEventStore store) {
        this.store = store;
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

        // 0 = no timeout; rely on infra timeouts / client reconnect
        final SseEmitter emitter = new SseEmitter(0L);
        final AtomicBoolean running = new AtomicBoolean(true);

        emitter.onCompletion(() -> running.set(false));
        emitter.onTimeout(() -> running.set(false));
        emitter.onError(e -> running.set(false));

        SSE_EXEC.execute(() -> {
            Cursor cursor = new Cursor();

            // If reconnecting, best-effort resume from last id (if still in ring).
            try {
                if (lastEventId != null && !lastEventId.isBlank()) {
                    DebugEvent last = store.get(lastEventId.trim());
                    if (last != null) {
                        cursor.lastTsMs = last.tsMs();
                        cursor.idsAtLastTs.add(last.id());
                    }
                }
            } catch (Throwable ignore) {
                // best-effort
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
                    running.set(false);
                }

                // Initial backlog (oldest -> newest)
                if (running.get()) {
                    List<DebugEvent> initial = safeList(initialLimit);
                    Collections.reverse(initial);
                    for (DebugEvent ev : initial) {
                        if (!running.get())
                            break;
                        try {
                            sendEvent(emitter, ev);
                            cursor.advance(ev);
                        } catch (IOException e) {
                            running.set(false);
                            break;
                        }
                    }
                }

                // Tail loop
                while (running.get()) {
                    List<DebugEvent> snapshot = safeList(Math.max(initialLimit, 120));
                    List<DebugEvent> toSend = new ArrayList<>();
                    for (DebugEvent ev : snapshot) {
                        if (ev == null)
                            continue;
                        if (cursor.isNew(ev)) {
                            toSend.add(ev);
                        }
                    }
                    // Send oldest -> newest to match tail -f reading order.
                    Collections.reverse(toSend);
                    for (DebugEvent ev : toSend) {
                        if (!running.get())
                            break;
                        try {
                            sendEvent(emitter, ev);
                            cursor.advance(ev);
                        } catch (IOException e) {
                            running.set(false);
                            break;
                        }
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeatAt >= hbMs) {
                        try {
                            emitter.send(SseEmitter.event().name("hb").comment("keep-alive"));
                        } catch (IOException e) {
                            running.set(false);
                            break;
                        }
                        lastHeartbeatAt = now;
                    }

                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        running.set(false);
                        break;
                    }
                }
            } finally {
                try {
                    emitter.complete();
                } catch (Throwable ignore) {
                }
            }
        });

        return emitter;
    }

    private List<DebugEvent> safeList(int lim) {
        try {
            return store.list(lim);
        } catch (Throwable ignore) {
            return List.of();
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
