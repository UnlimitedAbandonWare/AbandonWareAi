package ai.abandonware.nova.orch.trace;

import com.example.lms.search.TraceStore;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TraceStore-backed event log helper with deterministic sequencing.
 *
 * <p>Stores per-trace AtomicLong under TraceStore shared map so cross-thread append becomes sortable.</p>
 */
public final class OrchTrace {

    /** Versioned orchestration event list key. */
    public static final String TRACE_KEY_EVENTS_V1 = "orch.events.v1";

    private static final String SEQ_KEY_PREFIX = "__seq.orch.";

    private OrchTrace() {
    }

    public static long nextSeq(String scope) {
        String k = SEQ_KEY_PREFIX + (scope == null ? "default" : scope);
        Object existing = TraceStore.get(k);
        if (existing instanceof AtomicLong al) {
            return al.incrementAndGet();
        }
        AtomicLong created = new AtomicLong(0L);
        Object prev = TraceStore.putIfAbsent(k, created);
        AtomicLong al = (prev instanceof AtomicLong p) ? p : created;
        return al.incrementAndGet();
    }

    public static Map<String, Object> newEvent(
            String kind,
            String phase,
            String step,
            @Nullable Map<String, Object> data) {

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("v", 1);
        ev.put("kind", kind);
        ev.put("phase", phase);
        ev.put("step", step);
        ev.put("ts", Instant.now().toString());
        ev.put("thread", Thread.currentThread().getName());

        String sid = firstNonBlank(MDC.get("sid"), asString(TraceStore.get("sid")));
        String traceId = firstNonBlank(MDC.get("traceId"), asString(TraceStore.get("traceId")));
        String requestId = firstNonBlank(MDC.get("requestId"), asString(TraceStore.get("requestId")));

        if (sid != null) {
            ev.put("sid", sid);
        }
        if (traceId != null) {
            ev.put("traceId", traceId);
        }
        if (requestId != null) {
            ev.put("requestId", requestId);
        }
        if (data != null && !data.isEmpty()) {
            ev.put("data", data);
        }
        return ev;
    }

    public static void appendEvent(Map<String, Object> ev) {
        if (ev == null) {
            return;
        }
        if (!ev.containsKey("seq")) {
            ev.put("seq", nextSeq("events.v1"));
        }
        TraceStore.append(TRACE_KEY_EVENTS_V1, ev);

        Object kind = ev.get("kind");
        if (kind != null) {
            TraceStore.put("orch.events.v1.last." + kind, ev);
        }
    }

    @Nullable
    private static String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    @Nullable
    private static String asString(@Nullable Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
