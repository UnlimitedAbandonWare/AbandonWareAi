package com.example.lms.service.rag.rerank;

import java.util.Locale;
import java.util.Map;

/**
 * Central resolver for rerank-related knobs.
 *
 * <p>Goal: remove drift by parsing all known alias keys in one place and
 * exposing canonical values for downstream components.
 *
 * <p>This resolver is intentionally fail-soft:
 * <ul>
 *   <li>Absent/blank/invalid values return {@code null}.</li>
 *   <li>No exceptions are thrown.</li>
 * </ul>
 */
public final class RerankKnobResolver {

    private RerankKnobResolver() {
    }

    /**
     * Parsed rerank knobs from request-scoped metadata.
     */
    public record Resolved(
            /** Plan-level knob (nullable): whether cross-encoder reranking is enabled. */
            Boolean crossEncoderEnabled,
            /** Plan-level knob (nullable): desired backend (auto/onnx-runtime/embedding-model/noop). */
            String backend,
            /** Plan-level knob (nullable): how many docs to keep after rerank. */
            Integer topK,
            /** Plan-level knob (nullable): how many candidates to score in CE. */
            Integer ceTopK,
            /** Plan-level knob (nullable): whether ONNX backend is allowed. */
            Boolean onnxEnabled
    ) {
    }

    public static Resolved resolve(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return new Resolved(null, null, null, null, null);
        }

        Boolean ceEnabled = firstBool(meta,
                // canonical
                "rerank.crossEncoder.enabled",
                // aliases
                "cross_encoder.enabled",
                "crossEncoder.enabled",
                "use_cross_encoder",
                "useCrossEncoder");

        String backend = firstString(meta,
                "rerank.backend",
                "rerank_backend",
                "rerankBackend");

        Integer topK = firstInt(meta,
                "rerank.topK",
                "rerank.top_k",
                "rerank_top_k",
                "rerankTopK",
                "rerankTopN",
                "rerank.topN");

        Integer ceTopK = firstInt(meta,
                // canonical
                "rerank.ce.topK",
                // aliases
                "rerank.ceTopK",
                "rerank.ce_top_k",
                "rerank_ce_top_k",
                "rerankCeTopK",
                // alternate naming
                "rerank.candidateK",
                "rerank.candidate_k",
                "rerank_candidate_k",
                "rerankCandidateK");

        Boolean onnxEnabled = firstBool(meta,
                "onnx.enabled",
                "onnx_enabled",
                "onnxEnabled");

        return new Resolved(ceEnabled, backend, topK, ceTopK, onnxEnabled);
    }

    private static String firstString(Map<String, Object> meta, String... keys) {
        if (meta == null || meta.isEmpty() || keys == null) {
            return null;
        }
        for (String k : keys) {
            if (k == null || k.isBlank()) {
                continue;
            }
            Object v = meta.get(k);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static Integer firstInt(Map<String, Object> meta, String... keys) {
        if (meta == null || meta.isEmpty() || keys == null) {
            return null;
        }
        for (String k : keys) {
            if (k == null || k.isBlank()) {
                continue;
            }
            Object v = meta.get(k);
            Integer parsed = parseInt(v);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Boolean firstBool(Map<String, Object> meta, String... keys) {
        if (meta == null || meta.isEmpty() || keys == null) {
            return null;
        }
        for (String k : keys) {
            if (k == null || k.isBlank()) {
                continue;
            }
            Object v = meta.get(k);
            Boolean parsed = parseBool(v);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Integer parseInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return null;
            }
            // Some callers might pass booleans as strings.
            String lower = s.toLowerCase(Locale.ROOT);
            if ("true".equals(lower)) {
                return 1;
            }
            if ("false".equals(lower)) {
                return 0;
            }
            return Integer.parseInt(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Boolean parseBool(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "yes".equals(lower) || "y".equals(lower) || "1".equals(lower)) {
            return true;
        }
        if ("false".equals(lower) || "no".equals(lower) || "n".equals(lower) || "0".equals(lower)) {
            return false;
        }
        return null;
    }
}
