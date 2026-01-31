package com.example.lms.telemetry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MatrixTelemetryExtractor
 * - Extracts 9 core observability matrices (M1..M9) from a generic run summary map.
 * - Pure Java (no external deps). Fail-soft: never throws upstream.
 * - All metrics are normalized into [0,1] and missing keys are zero-filled.
 *
 * Expected input (best-effort): Map<String, Object> runSummary
 *  keys may include (examples, optional):
 *    "source.web.count", "source.vector.count", "source.kg.count",
 *    "authority.avg", "novelty.avg", "reranker.cost", "latency.ms",
 *    "contradiction.score", "risk.score", "budget.usage"
 *
 * Output snapshot keys (fixed schema):
 *    m1_source_mix_web, m1_source_mix_vector, m1_source_mix_kg
 *    m2_authority, m3_novelty, m4_contradiction, m5_rerank_cost
 *    m8_latency, m9_budget, m7_risk  (numbered to match doc order loosely)
 */
public class MatrixTelemetryExtractor {

    private static double nz(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ignore) {
            return def;
        }
    }

    private static double clip01(double x) {
        if (x < 0) return 0;
        if (x > 1) return 1;
        return x;
    }

    public Map<String, Object> extract(Map<String, Object> runSummary) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String,Object> m = (runSummary == null) ? new HashMap<>() : runSummary;

            // Source mix (normalize counts if provided)
            double web = nz(m.get("source.web.count"), 0);
            double vec = nz(m.get("source.vector.count"), 0);
            double kg  = nz(m.get("source.kg.count"), 0);
            double sum = Math.max(1.0, web + vec + kg);
            out.put("m1_source_mix_web", clip01(web / sum));
            out.put("m1_source_mix_vector", clip01(vec / sum));
            out.put("m1_source_mix_kg", clip01(kg / sum));

            // Authority [0..1]
            out.put("m2_authority", clip01(nz(m.get("authority.avg"), 0)));

            // Novelty [0..1] (1-highly novel)
            out.put("m3_novelty", clip01(nz(m.get("novelty.avg"), 0)));

            // Contradiction [0..1]
            out.put("m4_contradiction", clip01(nz(m.get("contradiction.score"), 0)));

            // Reranker cost normalized (if raw ms or tokens, apply log-normalization)
            double rrCost = nz(m.get("reranker.cost"), 0);
            double rrNorm = rrCost <= 0 ? 0 : Math.min(1.0, Math.log10(1 + rrCost) / 3.0);
            out.put("m5_rerank_cost", rrNorm);

            // Risk [0..1]
            out.put("m7_risk", clip01(nz(m.get("risk.score"), 0)));

            // Latency [ms] -> normalized via soft saturation around 3s
            double latMs = nz(m.get("latency.ms"), 0);
            double latNorm = Math.tanh(latMs / 3000.0);
            out.put("m8_latency", clip01(latNorm));

            // Budget usage [0..1]
            out.put("m9_budget", clip01(nz(m.get("budget.usage"), 0)));

            // Fixed vector slot for regression safety (ordered keys)
            out.put("_order", Arrays.asList(
                "m1_source_mix_web","m1_source_mix_vector","m1_source_mix_kg",
                "m2_authority","m3_novelty","m4_contradiction","m5_rerank_cost",
                "m7_risk","m8_latency","m9_budget"
            ));
        } catch (Exception e) {
            // Fail-soft: return zero-filled snapshot
            Map<String,Object> zero = new LinkedHashMap<>();
            zero.put("m1_source_mix_web", 0.0);
            zero.put("m1_source_mix_vector", 0.0);
            zero.put("m1_source_mix_kg", 0.0);
            zero.put("m2_authority", 0.0);
            zero.put("m3_novelty", 0.0);
            zero.put("m4_contradiction", 0.0);
            zero.put("m5_rerank_cost", 0.0);
            zero.put("m7_risk", 0.0);
            zero.put("m8_latency", 0.0);
            zero.put("m9_budget", 0.0);
            zero.put("_order", Arrays.asList(
                "m1_source_mix_web","m1_source_mix_vector","m1_source_mix_kg",
                "m2_authority","m3_novelty","m4_contradiction","m5_rerank_cost",
                "m7_risk","m8_latency","m9_budget"
            ));
            return zero;
        }
        return out;
    }
}