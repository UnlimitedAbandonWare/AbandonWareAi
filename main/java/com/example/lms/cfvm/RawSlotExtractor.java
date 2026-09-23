package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Lightweight "failure pattern signature" helper.
 *
 * <p>Motivation (DROP.txt / UAW.txt):
 * When orchestration degrades (aux-down, strike/bypass, web/vector fallback order),
 * we want a cheap way to cluster "similar collapses" without storing huge traces.
 *
 * <p>This class intentionally keeps the signature compact and stable.
 * It is fail-soft: missing keys simply become empty strings in the signature.
 */
@Component
public class RawSlotExtractor {

    /**
     * Backward compatible helper: hash a free-form text signature.
     */
    public long patternId(String text) {
        return SimHash64.hash(text);
    }

    /**
     * Compute a stable pattern id from a TraceStore snapshot.
     */
    public static long patternIdFromTrace(Map<String, Object> trace) {
        String sig = signature(trace);
        if (sig == null || sig.isBlank()) {
            TraceStore.put("cfvm.patternId.zero", true);
            return 0L;
        }
        long patternId = SimHash64.hash(sig);
        if (patternId == 0L) {
            TraceStore.put("cfvm.patternId.zero", true);
        }
        return patternId;
    }

    /** Compute the current thread TraceStore signature for read-only report tooling. */
    public static String currentSignature() {
        return signature(com.example.lms.search.TraceStore.getAll());
    }

    /**
     * Build a compact signature string from a trace snapshot.
     *
     * <p>Only includes a minimal set of keys so the signature is stable and does not explode in length.
     */
    public static String signature(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            TraceStore.put("cfvm.signature.empty", true);
            return null;
        }

        // Collect a minimal set of orchestration keys.
        String plan = safe(trace.get("plan.id"));
        String strike = safe(trace.get("mode.strike"));
        String bypass = safe(trace.get("mode.bypass"));
        String order = safe(trace.get("retrieval.order"));
        String webK = bucket(trace.get("retrieval.web.k"));
        String vecK = bucket(trace.get("retrieval.vector.k"));
        String qt = safe(trace.get("part.queryTransformer"));
        String dis = safe(trace.get("part.disambiguation"));
        String rer = safe(trace.get("part.rerankCrossEncoder"));
        String extremePrimary = safe(trace.get("extremez.risk.primaryCause"));
        String extremeReason = safe(trace.get("extremez.activation.reason"));
        String validationDecision = safe(trace.get("learning.validation.decision"));
        String hotspot = safe(trace.get("learning.error.hotspot"));
        String jb = numberLabel(trace.get("cfvm.jb.score"), 0.0d, 1.0d);
        String cb = numberLabel(trace.get("cfvm.cb.score"), 0.0d, 1.0d);
        String boltzmannTemp = numberLabel(trace.get("cfvm.rawBuffer.boltzmannTemp"), 0.0d, 100.0d);

        return String.join("|",
                "plan=" + plan,
                "strike=" + strike,
                "bypass=" + bypass,
                "order=" + order,
                "webK=" + webK,
                "vecK=" + vecK,
                "qt=" + qt,
                "dis=" + dis,
                "rer=" + rer,
                "extremePrimary=" + extremePrimary,
                "extremeReason=" + extremeReason,
                "validation=" + validationDecision,
                "hotspot=" + hotspot,
                "jb=" + jb,
                "cb=" + cb,
                "boltzTemp=" + boltzmannTemp
        );
    }

    private static String safe(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "_");
        if (s.isBlank()) {
            return "";
        }
        if ("evidence_gate".equals(s)) {
            return "evidence_gate";
        }
        if (s.contains("provider") || s.contains("disabled")) {
            return "provider_disabled";
        }
        if (s.contains("contradiction") || s.contains("conflict") || s.contains("evidence")) {
            return "evidence_conflict";
        }
        if (s.contains("starvation") || s.contains("after_filter") || s.contains("thin")) {
            return "evidence_starvation";
        }
        if (s.contains("retrieval") || s.contains("timeout") || s.contains("rate_limit") || s.contains("error_rate")) {
            return "retrieval_degraded";
        }
        if (s.contains("modelrequired") || s.contains("model_required") || s.contains("qtx.llm")) {
            return "model_required";
        }
        if (s.contains("final_gate")) {
            return "final_gate_failed";
        }
        if (s.contains("contamination") || s.contains("legacy_context")) {
            return "context_contamination";
        }
        if (s.contains("sample_score") || s.contains("threshold")) {
            return "threshold";
        }
        if (s.contains("requery")) {
            return "requery";
        }
        if (s.contains("writer") || s.contains("ingest")) {
            return "writer";
        }
        if (s.contains("accepted")) {
            return "accepted";
        }
        if (s.contains("rejected")) {
            return "rejected";
        }
        if (s.contains("brave")) {
            return "brave";
        }
        if (s.contains("fullscale")) {
            return "fullscale";
        }
        if (s.contains("safe")) {
            return "safe";
        }
        if (s.contains("bypass")) {
            return "bypass";
        }
        if (s.contains("strike")) {
            return "strike";
        }
        if (s.contains("web") && s.contains("vector")) {
            return "web_vector";
        }
        if (s.contains("web")) {
            return "web";
        }
        if (s.contains("vector")) {
            return "vector";
        }
        if (s.contains("kg")) {
            return "kg";
        }
        if (s.contains("selfask")) {
            return "selfask";
        }
        if (s.contains("rerank")) {
            return "rerank";
        }
        if ("true".equals(s) || "false".equals(s) || "on".equals(s) || "off".equals(s)) {
            return s;
        }
        if ("unknown".equals(s) || "none".equals(s)) {
            return "unknown";
        }
        return "other";
    }

    private static String bucket(Object value) {
        if (value == null) {
            return "";
        }
        double n;
        try {
            n = value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("cfvm.slot.bucket", ignore);
            return safe(value);
        }
        if (!Double.isFinite(n)) {
            return "other";
        }
        if (n <= 0) {
            return "k_zero";
        }
        if (n <= 4) {
            return "k_low";
        }
        if (n <= 12) {
            return "k_mid";
        }
        return "k_high";
    }

    private static String numberLabel(Object value, double min, double max) {
        if (value == null) {
            return "";
        }
        double n;
        try {
            n = value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("cfvm.slot.numberLabel", ignore);
            return safe(value);
        }
        if (!Double.isFinite(n)) {
            return "other";
        }
        double clamped = Math.max(min, Math.min(max, n));
        String formatted = String.format(java.util.Locale.ROOT, "%.2f", clamped);
        return formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static void traceSuppressed(String stage, RuntimeException ex) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("cfvm.slot.suppressed.stage", safeStage);
        TraceStore.put("cfvm.slot.suppressed." + safeStage, true);
        TraceStore.put("cfvm.slot.suppressed.errorType", errorType(ex));
    }

    private static String errorType(RuntimeException ex) {
        if (ex instanceof NumberFormatException) {
            return "invalid_number";
        }
        return ex == null ? "unknown" : ex.getClass().getSimpleName();
    }
}
