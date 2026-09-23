package ai.abandonware.nova.orch.trace;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable emitters for Trace/Breadcrumb/DebugEvent JSON. */
public final class OrchEventEmitter {

    private OrchEventEmitter() {
    }

    public static void breadcrumb(String kind, String phase, String step, @Nullable Map<String, Object> data) {
        OrchTrace.appendEvent(OrchTrace.newEvent(kind, phase, step, data));
    }

    public static void ragEvent(
            String kind,
            String phase,
            String stage,
            String step,
            String component,
            String status,
            @Nullable Map<String, Object> input,
            @Nullable Map<String, Object> output,
            @Nullable Map<String, Object> failure,
            @Nullable Map<String, Object> control) {
        OrchTrace.appendEvent(OrchTrace.newRagEvent(
                kind, phase, stage, step, component, status, input, output, failure, control));
    }

    public static void breadcrumbAndDebug(
            @Nullable DebugEventStore store,
            DebugProbeType probe,
            DebugEventLevel level,
            String fingerprint,
            String message,
            String where,
            String kind,
            String phase,
            String step,
            @Nullable Map<String, Object> data,
            @Nullable Throwable error) {

        Map<String, Object> ev = OrchTrace.newEvent(kind, phase, step, data);
        OrchTrace.appendEvent(ev);
        if (!isDebugEnabled() || store == null) {
            return;
        }

        Map<String, Object> debugData = new LinkedHashMap<>();
        if (data != null && !data.isEmpty()) {
            debugData.putAll(data);
        }
        Object seq = ev.get("seq");
        if (seq != null) {
            debugData.put("orch.seq", seq);
        }
        debugData.put("orch.kind", kind);
        debugData.put("orch.phase", phase);
        debugData.put("orch.step", step);

        try {
            store.emit(probe, level, fingerprint, message, where, debugData, error);
        } catch (RuntimeException failure) {
            try {
                TraceStore.inc("orch.debugEvent.emit.failed");
                TraceStore.put("orch.debugEvent.emit.failureClass", "orch_debug_event_emit_failed");
            } catch (RuntimeException ignore) {
                traceSuppressed("debugEvent.emit.traceFailure", ignore);
            }
        }
    }

    public static boolean isDebugEnabled() {
        if (truthy(MDC.get("dbgSearch"))) {
            return true;
        }
        try {
            return truthy(TraceStore.get("dbgSearch"));
        } catch (Throwable e) {
            traceSuppressed("isDebugEnabled", e);
            return false;
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        MDC.put("orch.debugEvent.suppressed.stage", stage);
        MDC.put("orch.debugEvent.suppressed.errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    private static boolean truthy(@Nullable Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        return s.equalsIgnoreCase("true") || s.equals("1")
                || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("y");
    }
}
