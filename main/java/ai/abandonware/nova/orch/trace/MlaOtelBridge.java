package ai.abandonware.nova.orch.trace;

import com.example.lms.trace.SafeRedactor;
import com.example.lms.search.TraceStore;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MlaOtelBridge {

    private static final String ENABLED_PROPERTY = "otel.mla.bridge.enabled";
    private static final String ENABLED_ENV = "OTEL_MLA_BRIDGE_ENABLED";
    private static volatile Boolean configuredEnabled = false;

    private MlaOtelBridge() {
    }

    public static void emit(Map<String, Object> event) {
        if (!enabled() || event == null || event.isEmpty()) {
            return;
        }
        try {
            Tracer tracer = GlobalOpenTelemetry.get().getTracer("demo1-mla");
            Span span = tracer.spanBuilder(spanName(event)).startSpan();
            try {
                applyAttributes(span, attributes(event));
                if ("error".equalsIgnoreCase(String.valueOf(event.get("status")))) {
                    span.setStatus(StatusCode.ERROR);
                }
            } finally {
                span.end();
            }
        } catch (Throwable e) {
            traceSuppressed("emit", e);
            // Telemetry must never affect orchestration.
        }
    }

    static boolean enabled() {
        String value = System.getProperty(ENABLED_PROPERTY);
        if (value != null && !value.isBlank()) {
            return Boolean.parseBoolean(value.trim());
        }
        value = System.getenv(ENABLED_ENV);
        if (value != null && !value.isBlank()) {
            return Boolean.parseBoolean(value.trim());
        }
        return Boolean.TRUE.equals(configuredEnabled);
    }

    public static void configure(boolean enabled) {
        configuredEnabled = enabled;
    }

    static Map<String, Object> attributes(Map<String, Object> event) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        putString(attrs, "mla.phase", event.get("phase"));
        putString(attrs, "mla.step", first(event.get("step"), event.get("stage")));
        putString(attrs, "mla.component", event.get("component"));
        putString(attrs, "mla.status", event.get("status"));
        attrs.put("gen_ai.operation.name", operationName(event));
        attrs.put("rag.retriever.kind", retrieverKind(event));
        putHash(attrs, "session.id.hash", first(first(event.get("sessionId"), event.get("sid")), event.get("sessionIdHash")));
        putHash(attrs, "request.id.hash", first(event.get("requestId"), event.get("requestIdHash")));
        putHash(attrs, "trace.id.hash", first(event.get("traceId"), event.get("traceIdHash")));
        putHash(attrs, "mla.anchor.hash", first(first(field(event, "control", "anchorHash"), field(event, "output", "anchorHash")),
                field(event, "traceAnchor", "anchorHash")));
        putLong(attrs, "rag.matrix.tile", first(first(field(event, "control", "matrixTile"), field(event, "output", "matrixTile")),
                field(event, "traceAnchor", "matrixTile")));
        putDouble(attrs, "rag.ablation.drop", first(first(field(event, "control", "ablationDrop"), field(event, "output", "ablationDrop")),
                field(event, "traceAnchor", "expectedDelta")));
        putString(attrs, "rag.route.hint", first(first(field(event, "control", "routeHint"), field(event, "output", "routeHint")),
                field(event, "traceAnchor", "routeHint")));
        emitCounts(attrs, event.get("output"));
        return attrs;
    }

    private static void applyAttributes(Span span, Map<String, Object> attrs) {
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number n) {
                if (value instanceof Float || value instanceof Double) {
                    span.setAttribute(entry.getKey(), n.doubleValue());
                } else {
                    span.setAttribute(entry.getKey(), n.longValue());
                }
            } else if (value instanceof Boolean b) {
                span.setAttribute(entry.getKey(), b);
            } else if (value != null) {
                span.setAttribute(entry.getKey(), String.valueOf(value));
            }
        }
    }

    private static void emitCounts(Map<String, Object> attrs, Object output) {
        if (!(output instanceof Map<?, ?> map)) {
            return;
        }
        putLong(attrs, "rag.returned_count", map.get("returnedCount"));
        putLong(attrs, "rag.after_filter_count", map.get("afterFilterCount"));
        putLong(attrs, "rag.selected_count", map.get("selectedCount"));
        putLong(attrs, "rag.promoted_count", map.get("promotedCount"));
        putLong(attrs, "rag.stage_ms", map.get("stageMs"));
        putDouble(attrs, "rag.source_diversity", map.get("sourceDiversity"));
    }

    private static String spanName(Map<String, Object> event) {
        String base = string(first(first(event.get("stage"), event.get("kind")), event.get("component")));
        if (base == null || base.isBlank()) {
            base = "orchestrate";
        }
        return "mla." + safeLabel(base, "orchestrate");
    }

    private static String operationName(Map<String, Object> event) {
        String joined = (string(event.get("kind")) + " " + string(event.get("stage")) + " " + string(event.get("component")))
                .toLowerCase(Locale.ROOT);
        if (joined.contains("retrieve") || joined.contains("search")) return "retrieve";
        if (joined.contains("ingest") || joined.contains("archive")) return "ingest";
        if (joined.contains("generate") || joined.contains("answer") || joined.contains("chat")) return "chat";
        if (joined.contains("tool")) return "tool_call";
        return "orchestrate";
    }

    private static String retrieverKind(Map<String, Object> event) {
        String joined = (string(event.get("kind")) + " " + string(event.get("stage")) + " " + string(event.get("component")))
                .toLowerCase(Locale.ROOT);
        if (joined.contains("conversation") || joined.contains("archive")) return "conversation_archive";
        if (joined.contains("vector")) return "vector";
        if (joined.contains("web") || joined.contains("search")) return "web";
        if (joined.contains("kg") || joined.contains("graph")) return "kg";
        if (joined.contains("memory")) return "memory";
        if (joined.contains("attach")) return "attachment";
        return "unknown";
    }

    private static void putString(Map<String, Object> attrs, String key, Object value) {
        String s = safeLabel(string(value), null);
        if (s != null && !s.isBlank()) {
            attrs.put(key, s);
        }
    }

    private static void putHash(Map<String, Object> attrs, String key, Object value) {
        String raw = string(value);
        if (raw == null || raw.isBlank()) {
            return;
        }
        String hash = raw.startsWith("hash:") ? raw.substring("hash:".length()) : SafeRedactor.hash12(raw);
        if (hash != null && !hash.isBlank()) {
            attrs.put(key, "hash:" + hash);
        }
    }

    private static void putLong(Map<String, Object> attrs, String key, Object value) {
        Long n = longValue(value);
        if (n != null) {
            attrs.put(key, n);
        }
    }

    private static void putDouble(Map<String, Object> attrs, String key, Object value) {
        Double n = doubleValue(value);
        if (n != null) {
            attrs.put(key, n);
        }
    }

    private static Object field(Map<String, Object> event, String mapKey, String valueKey) {
        Object raw = event == null ? null : event.get(mapKey);
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        return map.get(valueKey);
    }

    private static Object first(Object first, Object second) {
        String a = string(first);
        return a == null || a.isBlank() ? second : first;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            if (!Double.isFinite(numeric)) {
                traceSuppressed("longValue", new NumberFormatException("non-finite number"));
                return null;
            }
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            String s = String.valueOf(value).trim();
            return s.isBlank() ? null : Long.parseLong(s);
        } catch (NumberFormatException e) {
            traceSuppressed("longValue", e);
            return null;
        }
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            if (!Double.isFinite(numeric)) {
                traceSuppressed("doubleValue", new NumberFormatException("non-finite number"));
                return null;
            }
            return numeric;
        }
        if (value == null) {
            return null;
        }
        try {
            String s = String.valueOf(value).trim();
            if (s.isBlank()) {
                return null;
            }
            double numeric = Double.parseDouble(s);
            if (!Double.isFinite(numeric)) {
                throw new NumberFormatException("non-finite number");
            }
            return numeric;
        } catch (NumberFormatException e) {
            traceSuppressed("doubleValue", e);
            return null;
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        TraceStore.inc("mla.otel.suppressed.count");
        TraceStore.put("mla.otel.suppressed.stage", stage);
        String errorType = ("longValue".equals(stage) || "doubleValue".equals(stage))
                ? "invalid_number"
                : error == null ? "unknown" : error.getClass().getSimpleName();
        TraceStore.put("mla.otel.suppressed.errorType",
                errorType);
    }

    private static String safeLabel(String value, String fallback) {
        String raw = value == null || value.isBlank() ? fallback : value.trim();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String label = SafeRedactor.traceLabelOrFallback(raw, "");
        if (label.isBlank()) {
            return null;
        }
        String safe = label.replace("hash:", "hash_").replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return safe.substring(0, Math.min(120, safe.length()));
    }
}
