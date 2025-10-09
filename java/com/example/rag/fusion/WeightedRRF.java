package com.example.rag.fusion;

import java.util.*;

/**
 * Weighted Reciprocal Rank Fusion (RRF).
 * <p>
 * A compact, dependency‑free implementation that can be used to fuse ranked lists
 * coming from multiple retrievers. Scores are computed as
 *   sum_i weight[i] * 1 / (K + rank_i(item))
 * and the items are then sorted by the fused score in descending order.
 * K defaults to 60 as in the original RRF paper.
 * </p>
 */
public final class WeightedRRF {

    private static final int DEFAULT_K = 60;

    private WeightedRRF() {}

    /** Equal weights convenience. */
    public static <T> List<T> fuse(List<List<T>> rankings, int topK) {
        Objects.requireNonNull(rankings, "rankings");
        double[] w = new double[rankings.size()];
        Arrays.fill(w, 1.0);
        return fuse(rankings, w, topK, DEFAULT_K);
    }

    public static <T> List<T> fuse(List<List<T>> rankings, double[] weights, int topK, int K) {
        Objects.requireNonNull(rankings, "rankings");
        if (weights == null || weights.length != rankings.size()) {
            weights = new double[rankings.size()];
            Arrays.fill(weights, 1.0);
        }
        if (K <= 0) K = DEFAULT_K;

        Map<T, Double> score = new HashMap<>();
        for (int i = 0; i < rankings.size(); i++) {
            List<T> list = rankings.get(i);
            if (list == null) continue;
            double wi = weights[i];
            for (int r = 0; r < list.size(); r++) {
                T item = list.get(r);
                double add = wi * (1.0 / (K + r + 1));
                score.merge(item, add, Double::sum);
            }
        }
        List<Map.Entry<T, Double>> entries = new ArrayList<>(score.entrySet());
        entries.sort((a,b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            return (cmp != 0) ? cmp : Integer.compare(bestRank(rankings, a.getKey()),
                                                      bestRank(rankings, b.getKey()));
        });
        int k = Math.max(0, Math.min(topK, entries.size()));
        List<T> out = new ArrayList<>(k);
        for (int i = 0; i < k; i++) out.add(entries.get(i).getKey());
        return out;
    }

    private static <T> int bestRank(List<List<T>> rankings, T item) {
        int best = Integer.MAX_VALUE;
        for (List<T> list : rankings) {
            if (list == null) continue;
            int idx = list.indexOf(item);
            if (idx >= 0 && idx < best) best = idx;
        }
        return (best == Integer.MAX_VALUE) ? Integer.MAX_VALUE : best;
    }
}