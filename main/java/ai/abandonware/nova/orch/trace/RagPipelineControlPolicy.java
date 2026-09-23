package ai.abandonware.nova.orch.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Active-gated control mapping for RAG pipeline breadcrumbs.
 *
 * <p>The policy does not call external services or create a second recovery
 * engine. It converts verified reason codes into request-local control signals
 * that existing fail-soft/recovery/brave-aware code can consume or display.</p>
 */
public final class RagPipelineControlPolicy {

    public static final String MODE = "active_gated";

    private RagPipelineControlPolicy() {
    }

    public static Map<String, Object> normalize(
            String stage,
            String status,
            Map<String, Object> failure,
            Map<String, Object> output,
            Map<String, Object> requestedControl) {

        Map<String, Object> control = new LinkedHashMap<>();
        if (requestedControl != null && !requestedControl.isEmpty()) {
            control.putAll(requestedControl);
        }

        String reasonCode = firstNonBlank(
                str(control.get("reasonCode")),
                str(failure == null ? null : failure.get("reasonCode")),
                str(failure == null ? null : failure.get("failureClass")),
                inferReason(status));
        String action = firstNonBlank(str(control.get("action")), actionFor(reasonCode));
        Map<String, Object> traceAnchorControl = traceAnchorControl();
        if ((action == null || action.isBlank()) && !traceAnchorControl.isEmpty()) {
            control.putAll(traceAnchorControl);
            reasonCode = firstNonBlank(str(control.get("reasonCode")), reasonCode);
            action = firstNonBlank(str(control.get("action")), actionFor(reasonCode));
        } else if (!traceAnchorControl.isEmpty()) {
            copyTraceAnchorScalars(control, traceAnchorControl);
        }
        boolean applied = bool(control.get("applied"), action != null && !action.isBlank());

        if (action == null || action.isBlank()) {
            return control;
        }

        control.put("action", action);
        control.put("applied", applied);
        if (reasonCode != null && !reasonCode.isBlank()) {
            control.put("reasonCode", reasonCode);
        }
        if (!control.containsKey("breadcrumbId")) {
            Object breadcrumbId = output == null ? null : output.get("breadcrumbId");
            if (breadcrumbId != null) {
                control.put("breadcrumbId", String.valueOf(breadcrumbId));
            }
        }
        control.putIfAbsent("mode", MODE);

        if (applied) {
            applyTraceSignal(action, reasonCode, stage, control);
        }
        return control;
    }

    private static String actionFor(String rawReason) {
        String reason = norm(rawReason);
        if (reason.isBlank()) {
            return "";
        }
        if (reason.equals("zero_result")
                || reason.equals("trace_anchor_recovery")
                || reason.equals("retrieval_starvation")
                || reason.equals("weak_evidence")
                || reason.equals("after_filter_starvation")
                || reason.equals("insufficient_citations")
                || reason.equals("history_context_contamination")) {
            return "recovery";
        }
        if (reason.equals("provider_disabled")
                || reason.equals("trace_anchor_fallback")
                || reason.equals("missing_dependency")
                || reason.equals("rate_limit")
                || reason.equals("timeout")
                || reason.equals("fallback")
                || reason.equals("fallback_dependency")
                || reason.equals("latency_pressure")
                || reason.equals("silent_failure")) {
            return "fail_soft_fallback";
        }
        if (reason.equals("low_authority")
                || reason.equals("trace_anchor_brave")
                || reason.equals("low_coverage")
                || reason.equals("source_collapse")
                || reason.equals("thin_results")
                || reason.equals("stage_drop_high")
                || reason.equals("drop_bottleneck")) {
            return "brave_mode";
        }
        return "";
    }

    private static void applyTraceSignal(String action, String reasonCode, String stage, Map<String, Object> control) {
        try {
            TraceStore.put("rag.control.mode", MODE);
            TraceStore.put("rag.control.last.action", safeTraceScalar(action));
            if (reasonCode != null && !reasonCode.isBlank()) {
                TraceStore.put("rag.control.last.reasonCode", safeTraceScalar(reasonCode));
            }
            if (stage != null && !stage.isBlank()) {
                TraceStore.put("rag.control.last.stage", safeTraceScalar(stage));
            }
            Object breadcrumbId = control == null ? null : control.get("breadcrumbId");
            if (breadcrumbId != null) {
                TraceStore.put("rag.control.last.breadcrumbId", safeTraceScalar(breadcrumbId));
            }
            putIfPresent("rag.control.last.anchorHash", control == null ? null : control.get("anchorHash"));
            putIfPresent("rag.control.last.matrixTile", control == null ? null : control.get("matrixTile"));
            putIfPresent("rag.control.last.ablationDrop", control == null ? null : control.get("ablationDrop"));
            putIfPresent("rag.control.last.routeHint", control == null ? null : control.get("routeHint"));

            if ("recovery".equals(action)) {
                TraceStore.put("rag.control.recovery.requested", true);
            } else if ("fail_soft_fallback".equals(action)) {
                TraceStore.put("rag.control.fallback.requested", true);
            } else if ("brave_mode".equals(action)) {
                TraceStore.put("rag.control.brave.requested", true);
                TraceStore.put("rag.control.brave.mode", MODE);
            }
        } catch (Throwable e) {
            traceSuppressed("applyTraceSignal", e);
            // Control breadcrumbs must never affect the user request.
        }
    }

    private static Map<String, Object> traceAnchorControl() {
        try {
            Map<String, Object> top = firstNonEmptyMap(
                    TraceStore.get("ablation.traceAnchor.top"),
                    TraceStore.get("blackbox.risk.traceAnchor"));
            double pressure = max(
                    doubleValue(TraceStore.get("ablation.traceAnchor.routeCorrectionNeed")),
                    doubleValue(TraceStore.get("ablation.traceAnchor.maxExpectedDelta")),
                    doubleValue(top.get("routeCorrectionNeed")),
                    doubleValue(top.get("expectedDelta")));
            if (pressure < 0.65d || top.isEmpty()) {
                return Map.of();
            }
            String routeHint = safeLabel(firstNonBlank(
                    str(top.get("routeHint")),
                    str(TraceStore.get("ablation.traceAnchor.topRouteHint"))), "");
            String action = actionForRouteHint(routeHint);
            if (action.isBlank()) {
                return Map.of();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("action", action);
            out.put("applied", true);
            out.put("reasonCode", reasonForRouteHint(routeHint));
            out.put("anchorHash", safeLabel(str(top.get("anchorHash")), ""));
            out.put("matrixTile", Math.max(0L, Math.round(doubleValue(top.get("matrixTile")))));
            out.put("ablationDrop", round4(pressure));
            out.put("routeHint", routeHint);
            out.put("routeSource", "traceAnchor");
            return out;
        } catch (Throwable e) {
            traceSuppressed("traceAnchorControl", e);
            return Map.of();
        }
    }

    private static void copyTraceAnchorScalars(Map<String, Object> control, Map<String, Object> traceAnchorControl) {
        if (control == null || traceAnchorControl == null || traceAnchorControl.isEmpty()) {
            return;
        }
        for (String key : new String[]{"anchorHash", "matrixTile", "ablationDrop", "routeHint", "routeSource"}) {
            Object value = traceAnchorControl.get(key);
            if (value != null) {
                control.putIfAbsent(key, value);
            }
        }
    }

    private static String actionForRouteHint(String routeHint) {
        return switch (norm(routeHint)) {
            case "recovery" -> "recovery";
            case "fail_soft_fallback" -> "fail_soft_fallback";
            case "brave_mode" -> "brave_mode";
            default -> "";
        };
    }

    private static String reasonForRouteHint(String routeHint) {
        return switch (norm(routeHint)) {
            case "recovery" -> "trace_anchor_recovery";
            case "fail_soft_fallback" -> "trace_anchor_fallback";
            case "brave_mode" -> "trace_anchor_brave";
            default -> "trace_anchor";
        };
    }

    private static void putIfPresent(String key, Object value) {
        if (value != null) {
            TraceStore.put(key, safeTraceValue(value));
        }
    }

    private static Object safeTraceValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return safeTraceScalar(value);
    }

    private static String safeTraceScalar(Object value) {
        return SafeRedactor.traceLabelOrFallback(value, "");
    }

    private static Map<String, Object> firstNonEmptyMap(Object... values) {
        if (values == null) {
            return Map.of();
        }
        for (Object value : values) {
            if (value instanceof Map<?, ?> raw && !raw.isEmpty()) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (entry != null && entry.getKey() != null) {
                        out.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return out;
            }
        }
        return Map.of();
    }

    private static double max(double... values) {
        double out = 0.0d;
        if (values == null) {
            return out;
        }
        for (double value : values) {
            if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                out = Math.max(out, value);
            }
        }
        return out;
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (!Double.isFinite(parsed)) {
                traceSuppressed("doubleValue", new NumberFormatException("non-finite"));
                return 0.0d;
            }
            return parsed;
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            String s = String.valueOf(value).trim();
            if (s.isBlank()) {
                return 0.0d;
            }
            double parsed = Double.parseDouble(s);
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException("non-finite");
            }
            return parsed;
        } catch (NumberFormatException e) {
            traceSuppressed("doubleValue", e);
            return 0.0d;
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        try {
            TraceStore.inc("rag.control.suppressed.count");
            TraceStore.put("rag.control.suppressed.stage", stage);
            TraceStore.put("rag.control.suppressed.errorType", errorType(stage, error));
        } catch (Throwable traceFailure) {
            traceSuppressedFallback(stage, error, traceFailure);
        }
    }

    private static void traceSuppressedFallback(String stage, Throwable error, Throwable traceFailure) {
        MDC.put("rag.control.suppressed.stage", stage);
        MDC.put("rag.control.suppressed.errorType", errorType(stage, error));
        MDC.put("rag.control.suppressed.traceFailureType", errorType(traceFailure));
    }

    private static String errorType(String stage, Throwable error) {
        return "doubleValue".equals(stage) ? "invalid_number" : errorType(error);
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    private static double round4(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private static String inferReason(String status) {
        String s = norm(status);
        if (s.equals("empty") || s.equals("failed_emptyresult") || s.equals("failed_empty_result")) {
            return "zero_result";
        }
        if (s.equals("fallback")) {
            return "fallback";
        }
        return "";
    }

    private static String norm(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(':', '_')
                .replaceAll("[^a-z0-9_.]+", "_")
                .replaceAll("_+", "_");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String safeLabel(String value, String fallback) {
        String raw = value == null || value.isBlank() ? fallback : value.trim();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String label = SafeRedactor.traceLabelOrFallback(raw, "");
        if (label.isBlank()) {
            return "";
        }
        String safe = label.replace("hash:", "hash_").replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return safe.substring(0, Math.min(120, safe.length()));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        if (s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on")) {
            return true;
        }
        if (s.equalsIgnoreCase("false") || s.equals("0") || s.equalsIgnoreCase("no") || s.equalsIgnoreCase("off")) {
            return false;
        }
        return fallback;
    }
}
