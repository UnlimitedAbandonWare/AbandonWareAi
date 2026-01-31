package com.example.rag.fusion;
import java.util.*;

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

    public void setK(int k) { this.k = Math.max(1, k); }

    /** Combine 3 source scores (or 1/rank). Safe and dependency-free. */
    public double fuse(double webScore, double vectorScore, double kgScore) {
        double sw = 1.0 / (k + Math.max(0.0, webScore));
        double sv = 1.0 / (k + Math.max(0.0, vectorScore));
        double sk = 1.0 / (k + Math.max(0.0, kgScore));
        double s = alphaWeb * sw + alphaVector * sv + alphaKg * sk;
        // map to [0,1] approximately
        return Math.tanh(s);
    }


    /**
     * Legacy helper used by legacy SelfAsk planner variants that operate on
     * maps instead of typed ContextSlice objects. Each map is expected to
     * have keys: id (String), score (Number, optional).
     */
    public static java.util.List<java.util.Map<String,Object>> fuse(java.util.List<java.util.List<java.util.Map<String,Object>>> perBranch, int topK) {
        if (perBranch == null) return java.util.Collections.emptyList();
        java.util.Map<String, Double> agg = new java.util.HashMap<>();
        java.util.Map<String, java.util.Map<String,Object>> repr = new java.util.LinkedHashMap<>();
        final int kk = Math.max(1, topK);

for (java.util.List<java.util.Map<String,Object>> list : perBranch) {
            if (list == null) continue;
            int r = 1;
            for (java.util.Map<String,Object> m : list) {
                if (m == null) { r++; continue; }
                Object idObj = m.get("id");
                if (idObj == null) { r++; continue; }
                String id = String.valueOf(idObj);
                double term = 1.0 / (kk + r);
                double raw = 0.0;
                Object sc = m.get("score");
                if (sc instanceof Number) raw = ((Number) sc).doubleValue();
                double contrib = term * (0.75 + 0.5 * (raw < 0 ? 0 : (raw > 1 ? 1 : raw)));
                agg.merge(id, contrib, Double::sum);
                repr.putIfAbsent(id, new java.util.LinkedHashMap<>(m));
                r++;
            }
        }
        // sort by fused score desc and take topK
        java.util.List<java.util.Map.Entry<String, Double>> entries = new java.util.ArrayList<>(agg.entrySet());
        entries.sort((a,b) -> Double.compare(b.getValue(), a.getValue()));

        java.util.List<java.util.Map<String,Object>> out = new java.util.ArrayList<>();
        int count = Math.max(1, topK);
        int i=0;
        for (java.util.Map.Entry<String, Double> e : entries) {
            java.util.Map<String,Object> m = new java.util.LinkedHashMap<>(repr.get(e.getKey()));
            m.put("score", Math.tanh(e.getValue()));
            out.add(m);
            if (++i >= count) break;
        }
        return out;
    }
}