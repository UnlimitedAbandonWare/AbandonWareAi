package com.example.lms.service.rag.fusion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Extended Weighted Reciprocal Rank Fusion supporting optional power-mean
 * aggregation and URL canonicalization.  This implementation accepts
 * generic JSON-like maps as input in order to interoperate with
 * heterogeneous retrievers without requiring a strongly typed domain
 * model.
 */
@Component
public class WeightedRRF2 {
    private final boolean wpmEnabled;
    private final double p;
    private final ScoreCalibrator calibrator;
    private final PowerMeanFuser wpm;
    private final RerankCanonicalizer canonicalizer;

    public WeightedRRF2(@Value("${fusion.wpm.enabled:true}") boolean wpmEnabled,
                        @Value("${fusion.wpm.p:1.5}") double p,
                        ScoreCalibrator calibrator,
                        PowerMeanFuser wpm,
                        RerankCanonicalizer canonicalizer) {
        this.wpmEnabled = wpmEnabled;
        this.p = p;
        this.calibrator = calibrator;
        this.wpm = wpm;
        this.canonicalizer = canonicalizer;
    }

    /**
     * Fuse multiple ranked lists into a single ranking.  Each entry is
     * expected to be a map containing at least {@code id}, {@code score}
     * and optionally {@code url}.  The fused score is placed into a
     * {@code fusedScore} field on the returned maps and the entries are
     * sorted by decreasing fusedScore.
     *
     * @param inputs a list of ranked lists by source
     * @return a fused and sorted list of maps
     */
    public List<Map<String,Object>> fuse(List<List<Map<String,Object>>> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        // Canonicalize URLs first to deduplicate
        for (List<Map<String,Object>> list : inputs) {
            for (Map<String,Object> m : list) {
                Object url = m.get("url");
                if (url instanceof String s) {
                    m.put("url", canonicalizer.canonicalizeUrl(s));
                }
            }
        }
        final int K = 60;
        Map<String, Double> fused = new HashMap<>();
        Map<String, Map<String,Object>> repr = new HashMap<>();
        // Basic RRF
        for (List<Map<String,Object>> list : inputs) {
            int rank = 1;
            for (Map<String,Object> m : list) {
                String id = keyOf(m);
                double w = 1.0;
                double rrf = w / (K + rank);
                fused.merge(id, rrf, Double::sum);
                Map<String,Object> prev = repr.get(id);
                if (prev == null || ((Number)m.getOrDefault("score",0)).doubleValue() > ((Number)prev.getOrDefault("score",0)).doubleValue()) {
                    repr.put(id, m);
                }
                rank++;
            }
        }
        // Optional WPM augmentation
        if (wpmEnabled) {
            Map<String, List<Double>> perDoc = new HashMap<>();
            for (List<Map<String,Object>> list : inputs) {
                // build sample for normalization
                List<Double> sample = new ArrayList<>(list.size());
                for (Map<String,Object> m : list) {
                    sample.add(((Number)m.getOrDefault("score",0.0)).doubleValue());
                }
                for (Map<String,Object> m : list) {
                    String id = keyOf(m);
                    double raw = ((Number)m.getOrDefault("score",0.0)).doubleValue();
                    double norm = calibrator.normalizeBySample(raw, sample);
                    perDoc.computeIfAbsent(id, kk -> new ArrayList<>()).add(norm);
                }
            }
            perDoc.forEach((id, scores) -> {
                double pm = wpm.fuse(scores);
                fused.merge(id, 0.5 * pm, Double::sum);
            });
        }
        // Assemble result
        List<Map<String,Object>> out = new ArrayList<>();
        for (var e : fused.entrySet()) {
            Map<String,Object> m = new LinkedHashMap<>(repr.get(e.getKey()));
            m.put("fusedScore", e.getValue());
            out.add(m);
        }
        out.sort((a,b) -> Double.compare(((Number)b.get("fusedScore")).doubleValue(), ((Number)a.get("fusedScore")).doubleValue()));
        int r = 1;
        for (Map<String,Object> m : out) {
            m.put("rank", r++);
        }
        return out;
    }

    private static String keyOf(Map<String,Object> m) {
        Object url = m.get("url");
        if (url != null) return String.valueOf(url);
        Object id = m.get("id");
        return id != null ? String.valueOf(id) : UUID.randomUUID().toString();
    }
}