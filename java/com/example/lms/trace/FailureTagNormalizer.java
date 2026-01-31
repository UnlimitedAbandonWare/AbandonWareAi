package com.example.lms.trace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FailureTagNormalizer
 *
 * <p>
 * Normalizes runtime signals (TraceStore snapshot + model route) into
 * a small set of stable, low-cardinality tags.
 *
 * <p>
 * Primary composite tag (example): AFR_TR
 * - compression active + evidence-only + vector hit
 */
public final class FailureTagNormalizer {

    private FailureTagNormalizer() {
    }

    public static List<String> normalize(Map<String, Object> trace,
                                         String modelUsed,
                                         Throwable errorOrNull) {
        if (trace == null) trace = java.util.Collections.emptyMap();

        LinkedHashSet<String> tags = new LinkedHashSet<>();

        // ORCH / mode
        String mode = asString(trace.get("orch.mode"));
        if (!mode.isBlank()) {
            tags.add("ORCH_MODE:" + mode.toUpperCase(Locale.ROOT));
        }
        if (truthy(trace.get("orch.compression")) || truthy(trace.get("rag.compress.applied"))) {
            tags.add("ORCH_COMPRESSION:on");
        }
        if (truthy(trace.get("orch.strike"))) {
            tags.add("ORCH_STRIKE:on");
        }
        String reasons = asString(trace.get("orch.reason"));
        if (reasons.isBlank()) reasons = asString(trace.get("orch.reasons"));
        if (!reasons.isBlank()) {
            for (String tok : reasons.split("[,\\s]+")) {
                String t = tok == null ? "" : tok.trim();
                if (!t.isBlank()) tags.add("ORCH_REASON:" + t);
            }
        }

        // Retrieval hit
        int vec = sizeOf(trace.get("finalVectorTopK"));
        int web = sizeOf(trace.get("finalWebTopK"));
        tags.add(vec > 0 ? "VEC_HIT" : "VEC_MISS");
        tags.add(web > 0 ? "WEB_HIT" : "WEB_MISS");

        // Guard / answer mode
        String guardAction = asString(trace.get("guard.action"));
        if (!guardAction.isBlank()) {
            tags.add("GUARD_ACTION:" + guardAction.toUpperCase(Locale.ROOT));
        }

        String answerMode = asString(trace.get("answer.mode"));
        boolean evidenceOnlyGuard = guardAction.equalsIgnoreCase("REWRITE") || truthy(trace.get("guard.degradedToEvidence"))
                || answerMode.equalsIgnoreCase("EVIDENCE_ONLY");

        boolean evidenceOnlyFallback = (modelUsed != null && modelUsed.contains("fallback:evidence"))
                || answerMode.equalsIgnoreCase("FALLBACK_EVIDENCE");

        if (evidenceOnlyFallback) {
            tags.add("ANSWER_MODE:FALLBACK_EVIDENCE");
        } else if (evidenceOnlyGuard) {
            tags.add("ANSWER_MODE:EVIDENCE_ONLY");
        } else {
            tags.add("ANSWER_MODE:NORMAL");
        }

        // Infra/provider errors
        String errMsg = errorOrNull == null ? "" : String.valueOf(errorOrNull.getMessage());
        if (!errMsg.isBlank() && errMsg.toLowerCase(Locale.ROOT).contains("model is required")) {
            tags.add("PROVIDER_MODEL_REQUIRED");
        }

        // Composite: AFR_TR
        boolean compressionActive = truthy(trace.get("orch.compression")) || truthy(trace.get("rag.compress.applied"));
        boolean vecHit = vec > 0;
        if (compressionActive && vecHit && (evidenceOnlyFallback || evidenceOnlyGuard)) {
            tags.add("AFR_TR");
            tags.add("AFR_TR:COMPRESS_ON");
            tags.add("AFR_TR:VEC_HIT");
            tags.add(evidenceOnlyFallback ? "AFR_TR:EVIDENCE_ONLY_FALLBACK" : "AFR_TR:EVIDENCE_ONLY_GUARD");
        }

        return new ArrayList<>(tags);
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("on") || s.equals("yes") || s.equals("y");
    }

    private static String asString(Object v) {
        if (v == null) return "";
        return String.valueOf(v).trim();
    }

    private static int sizeOf(Object v) {
        if (v == null) return 0;
        if (v instanceof Collection<?> c) return c.size();
        if (v.getClass().isArray()) {
            try {
                return java.lang.reflect.Array.getLength(v);
            } catch (Exception ignore) {
                return 0;
            }
        }
        return 0;
    }
}
