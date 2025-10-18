
package com.abandonware.ai.service.rag.fusion;

import java.util.*;
import com.abandonware.ai.service.rag.model.ContextSlice;

/** Minimal weighted Reciprocal Rank Fusion (RRF) reference impl. */
public class WeightedRRF {

    private double alphaWeb = 0.8;
    private double alphaVector = 0.8;
    private double alphaKg = 0.8;
    private int k = 60; // RRF constant

    public WeightedRRF() {}

    public void setAlphas(double alphaWeb, double alphaVector, double alphaKg) {
        this.alphaWeb = alphaWeb;
        this.alphaVector = alphaVector;
        this.alphaKg = alphaKg;
    }

    public double[] getAlphas() { return new double[]{alphaWeb, alphaVector, alphaKg}; }

    public int getK() { return k; }

    public void setK(int k) { this.k = Math.max(1, k); }

    /**
     * Combine 3 source scores (or ranks) into a single score using weighted RRF.
     * This legacy numeric overload is kept for backward compatibility.
     */
    public double fuse(double webScore, double vectorScore, double kgScore) {
        double sw = 1.0 / (k + Math.max(0.0, webScore));
        double sv = 1.0 / (k + Math.max(0.0, vectorScore));
        double sk = 1.0 / (k + Math.max(0.0, kgScore));
        double s = alphaWeb * sw + alphaVector * sv + alphaKg * sk;
        // map to [0,1] approximately
        return Math.tanh(s);
    }

    /**
     * New overload used by FusionService/RrfFusion:
     * Fuse heterogeneous sources of ContextSlice lists.
     *
     * @param sources per-source lists (e.g., web/vector/kg). Duplicates by id are allowed.
     * @param k       RRF constant; if <=0 uses default.
     * @param weights optional source weight map (keyed by slice.getSource()).
     * @param calibrator optional score calibrator (raw->normalized [0,1]).
     * @param dedupe if true, keep the best scoring slice per id.
     * @return map of id -> fused ContextSlice (score set to fused score)
     */
    public Map<String, ContextSlice> fuse(
            List<List<ContextSlice>> sources,
            int k,
            Map<String, Double> weights,
            ScoreCalibrator calibrator,
            boolean dedupe
    ) {
        int kk = (k > 0 ? k : this.k);
        Map<String, ContextSlice> best = new LinkedHashMap<>();
        Map<String, Double> fused = new HashMap<>();

        if (sources == null) return best;

        for (List<ContextSlice> list : sources) {
            if (list == null) continue;
            int rank = 1;
            for (ContextSlice cs : list) {
                if (cs == null || cs.getId() == null) { rank++; continue; }
                String id = cs.getId();
                String src = cs.getSource() == null ? "" : cs.getSource();
                double alpha = 1.0;
                if (weights != null && !weights.isEmpty()) {
                    alpha = weights.getOrDefault(src, weights.getOrDefault("*", 1.0));
                }

                // RRF term based on rank
                double term = alpha * (1.0 / (kk + rank));

                // Optional calibration factor based on raw score
                double calib = 1.0;
                if (calibrator != null) {
                    try {
                        double norm = calibrator.normalize(cs.getScore(), src);
                        // boost within [0.75, 1.25] using normalized score
                        calib = 0.75 + 0.5 * clamp01(norm);
                    } catch (Throwable ignore) {}
                }

                double contrib = term * calib;
                fused.merge(id, contrib, Double::sum);

                // keep the most informative slice as representative
                if (!best.containsKey(id)) {
                    best.put(id, cloneSlice(cs));
                } else {
                    // prefer longer snippet/title, otherwise keep existing
                    ContextSlice cur = best.get(id);
                    int curLen = ((cur.getSnippet() == null?0:cur.getSnippet().length())
                                  + (cur.getTitle()==null?0:cur.getTitle().length()));
                    int newLen = ((cs.getSnippet() == null?0:cs.getSnippet().length())
                                  + (cs.getTitle()==null?0:cs.getTitle().length()));
                    if (newLen > curLen) best.put(id, cloneSlice(cs));
                }
                rank++;
            }
        }

        // Apply fused score and return
        for (Map.Entry<String, ContextSlice> e : best.entrySet()) {
            double s = fused.getOrDefault(e.getKey(), 0.0);
            e.getValue().setScore(clamp01(Math.tanh(s))); // keep in [0,1]
        }

        // If dedupe=false, expand into all items but here we return best-per-id map
        return best;
    }

    private static double clamp01(double x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }

    private static ContextSlice cloneSlice(ContextSlice c) {
        ContextSlice x = new ContextSlice();
        x.setId(c.getId());
        x.setTitle(c.getTitle());
        x.setSnippet(c.getSnippet());
        x.setSource(c.getSource());
        x.setScore(c.getScore());
        x.setRank(c.getRank());
        return x;
    }

    /**
     * Legacy helper used by legacy SelfAsk planner variants that operate on
     * maps instead of typed ContextSlice objects. Each map is expected to
     * have keys: id (String), score (Number, optional).
     */
    public static List<Map<String,Object>> fuse(List<List<Map<String,Object>>> perBranch, int topK) {
        if (perBranch == null) return java.util.Collections.emptyList();
        Map<String, Double> agg = new HashMap<>();
        Map<String, Map<String,Object>> repr = new LinkedHashMap<>();
        final int kk = Math.max(1, topK);

        for (List<Map<String,Object>> list : perBranch) {
            if (list == null) continue;
            int r = 1;
            for (Map<String,Object> m : list) {
                if (m == null) { r++; continue; }
                Object idObj = m.get("id");
                if (idObj == null) { r++; continue; }
                String id = String.valueOf(idObj);
                double term = 1.0 / (kk + r);
                double raw = 0.0;
                Object sc = m.get("score");
                if (sc instanceof Number) raw = ((Number) sc).doubleValue();
                double contrib = term * (0.75 + 0.5 * clamp01(raw));
                agg.merge(id, contrib, Double::sum);
                repr.putIfAbsent(id, new LinkedHashMap<>(m));
                r++;
            }
        }
        // sort by fused score desc and take topK
        List<Map.Entry<String, Double>> entries = new ArrayList<>(agg.entrySet());
        entries.sort((a,b) -> Double.compare(b.getValue(), a.getValue()));

        List<Map<String,Object>> out = new ArrayList<>();
        int count = Math.max(1, topK);
        int i=0;
        for (Map.Entry<String, Double> e : entries) {
            Map<String,Object> m = new LinkedHashMap<>(repr.get(e.getKey()));
            m.put("score", clamp01(Math.tanh(e.getValue())));
            out.add(m);
            if (++i >= count) break;
        }
        return out;
    }
}
