package ai.abandonware.nova.orch.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
            ev.put("sid", hashOrNull(sid));
        }
        if (traceId != null) {
            ev.put("traceId", hashOrNull(traceId));
        }
        if (requestId != null) {
            ev.put("requestId", hashOrNull(requestId));
        }
        if (data != null && !data.isEmpty()) {
            ev.put("data", data);
        }
        return ev;
    }

    public static Map<String, Object> newRagEvent(
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

        Map<String, Object> ev = new LinkedHashMap<>();
        String rawStatus = safeLabel(status, "ok");
        ev.put("v", 1);
        ev.put("kind", safeLabel(kind, "rag.pipeline"));
        ev.put("phase", safeLabel(phase, "unknown"));
        ev.put("stage", safeLabel(stage, "unknown"));
        ev.put("step", safeLabel(step, "complete"));
        ev.put("component", safeLabel(component, "unknown"));
        ev.put("status", normalizeStatus(rawStatus));
        ev.put("ts", Instant.now().toString());

        String sid = firstNonBlank(MDC.get("sid"), MDC.get("sessionId"), asString(TraceStore.get("sessionId")), asString(TraceStore.get("sid")));
        String traceId = firstNonBlank(MDC.get("traceId"), MDC.get("trace"), asString(TraceStore.get("traceId")), asString(TraceStore.get("trace.id")));
        String requestId = firstNonBlank(MDC.get("x-request-id"), MDC.get("requestId"), asString(TraceStore.get("requestId")), traceId);

        ev.put("traceId", hashOrEmpty(traceId));
        ev.put("sessionId", hashOrEmpty(sid));
        ev.put("requestId", hashOrEmpty(requestId));
        ev.putAll(TraceStore.searchCorrelation(TraceStore.context()));
        ev.put("input", sanitizeMap("input", input));
        ev.put("output", sanitizeMap("output", output));
        Map<String, Object> safeFailure = sanitizeMap("failure", failure);
        ev.put("failure", safeFailure);
        ev.put("control", sanitizeMap("control",
                RagPipelineControlPolicy.normalize(stage, rawStatus, safeFailure, sanitizeMap("output", output), control)));
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
        MlaOtelBridge.emit(ev);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private static String asString(@Nullable Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String hashOrEmpty(@Nullable String value) {
        String hashed = hashOrNull(value);
        return hashed == null ? "" : hashed;
    }

    @Nullable
    private static String hashOrNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("hash:") ? trimmed : SafeRedactor.hashValue(trimmed);
    }

    private static Map<String, Object> sanitizeMap(String prefix, @Nullable Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String key = safeLabel(entry.getKey(), "field");
            if (!isContractField(prefix, key)) {
                continue;
            }
            out.put(key, sanitizeValue(prefix + "." + key, entry.getValue(), 0));
            if (++count >= 64) {
                out.put("_truncated", true);
                break;
            }
        }
        return out;
    }

    private static boolean isContractField(String prefix, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String p = prefix == null ? "" : prefix;
        return switch (p) {
            case "input" -> key.equals("queryHash")
                    || key.equals("queryLen")
                    || key.equals("requestedTopK")
                    || key.equals("planId")
                    || key.equals("mode");
            case "output" -> key.equals("returnedCount")
                    || key.equals("naverRetainedCount")
                    || key.equals("afterFilterCount")
                    || key.equals("selectedCount")
                    || key.equals("promotedCount")
                    || key.equals("stageMs")
                    || key.equals("sourceDiversity")
                    || key.equals("anchorHash")
                    || key.equals("matrixTile")
                    || key.equals("ablationDrop")
                    || key.equals("routeHint")
                    || key.equals("routeSource");
            case "failure" -> key.equals("reasonCode")
                    || key.equals("failureClass")
                    || key.equals("exceptionType");
            case "control" -> key.equals("action")
                    || key.equals("applied")
                    || key.equals("reasonCode")
                    || key.equals("breadcrumbId")
                    || key.equals("anchorHash")
                    || key.equals("matrixTile")
                    || key.equals("ablationDrop")
                    || key.equals("routeHint")
                    || key.equals("routeSource");
            default -> true;
        };
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(String key, Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > 4) {
            return "(depth-limit)";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                String childKey = safeLabel(String.valueOf(entry.getKey()), "field");
                out.put(childKey, sanitizeValue(key + "." + childKey, entry.getValue(), depth + 1));
                if (++count >= 64) {
                    out.put("_truncated", true);
                    break;
                }
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            int count = 0;
            for (Object item : collection) {
                out.add(sanitizeValue(key, item, depth + 1));
                if (++count >= 64) {
                    out.add("(truncated)");
                    break;
                }
            }
            return out;
        }
        String s = String.valueOf(value);
        String k = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        if (k.contains("queryhash") || k.contains("hash") || k.endsWith(".reasoncode")
                || k.endsWith(".failureclass") || k.endsWith(".exceptiontype")
                || k.endsWith(".action") || k.endsWith(".status") || k.endsWith(".mode")
                || k.endsWith(".stage") || k.endsWith(".component") || k.endsWith(".breadcrumbid")
                || k.endsWith(".routehint") || k.endsWith(".routesource")
                || k.endsWith(".planid")) {
            return limit(SafeRedactor.redact(s).replace('\n', ' ').replace('\r', ' ').trim(), 240);
        }
        return SafeRedactor.diagnosticValue(key, s, 800);
    }

    private static String safeLabel(String value, String fallback) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank()) {
            return fallback;
        }
        String label = SafeRedactor.traceLabelOrFallback(raw, fallback);
        String safe = label.replace("hash:", "hash_").replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return limit(safe, 120);
    }

    private static String normalizeStatus(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank()) {
            return "ok";
        }
        String status = raw.toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(':', '_')
                .replaceAll("[^a-z0-9_.]+", "_")
                .replaceAll("_+", "_");
        if (status.equals("fallback") || status.equals("fail_soft_fallback")) {
            return "fallback";
        }
        if (status.equals("blocked")
                || status.equals("empty")
                || status.equals("zero_result")
                || status.equals("failed_emptyresult")
                || status.equals("failed_empty_result")) {
            return "blocked";
        }
        if (status.equals("error")
                || status.equals("failed")
                || status.equals("failure")
                || status.equals("exception")
                || status.startsWith("failed_")
                || status.contains("_error")
                || status.contains("_exception")) {
            return "error";
        }
        return "ok";
    }

    private static String limit(String s, int max) {
        if (s == null) {
            return null;
        }
        int lim = Math.max(16, max);
        return s.length() <= lim ? s : s.substring(0, lim);
    }
}
