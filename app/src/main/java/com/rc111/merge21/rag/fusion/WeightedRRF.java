package com.rc111.merge21.rag.fusion;

import java.util.Map;

public class WeightedRRF {
    private final Map<String, Double> w;
    private final int k;

    public WeightedRRF(Map<String, Double> weights, int k) {
        this.w = weights;
        this.k = Math.max(1, k);
    }

    /** source-specific weighted RRF: w_s * 1 / (k + rank) */
    public double score(String source, int rank1based) {
        double ws = w.getOrDefault(source == null ? "" : source.toLowerCase(), 1.0);
        return ws * (1.0 / (k + rank1based));
    }
}
