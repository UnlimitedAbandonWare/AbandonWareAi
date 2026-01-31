package com.example.lms.cfvm;

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
        return SimHash64.hash(sig);
    }

    /**
     * Build a compact signature string from a trace snapshot.
     *
     * <p>Only includes a minimal set of keys so the signature is stable and does not explode in length.
     */
    public static String signature(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) return "empty";

        // Collect a minimal set of orchestration keys.
        String plan = String.valueOf(trace.getOrDefault("plan.id", ""));
        String strike = String.valueOf(trace.getOrDefault("mode.strike", ""));
        String bypass = String.valueOf(trace.getOrDefault("mode.bypass", ""));
        String order = String.valueOf(trace.getOrDefault("retrieval.order", ""));
        String webK = String.valueOf(trace.getOrDefault("retrieval.web.k", ""));
        String vecK = String.valueOf(trace.getOrDefault("retrieval.vector.k", ""));
        String qt = String.valueOf(trace.getOrDefault("part.queryTransformer", ""));
        String dis = String.valueOf(trace.getOrDefault("part.disambiguation", ""));
        String rer = String.valueOf(trace.getOrDefault("part.rerankCrossEncoder", ""));

        return String.join("|",
                "plan=" + plan,
                "strike=" + strike,
                "bypass=" + bypass,
                "order=" + order,
                "webK=" + webK,
                "vecK=" + vecK,
                "qt=" + qt,
                "dis=" + dis,
                "rer=" + rer
        );
    }
}
