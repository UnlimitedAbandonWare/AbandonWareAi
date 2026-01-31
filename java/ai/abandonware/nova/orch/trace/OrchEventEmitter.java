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

        store.emit(probe, level, fingerprint, message, where, debugData, error);
    }

    public static boolean isDebugEnabled() {
        if (truthy(MDC.get("dbgSearch"))) {
            return true;
        }
        try {
            return truthy(TraceStore.get("dbgSearch"));
        } catch (Throwable ignore) {
            return false;
        }
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
