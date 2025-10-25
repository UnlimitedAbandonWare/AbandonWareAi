package com.abandonware.ai.normalization.service.rag.rerank;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Determinantal Point Process (DPP) based diversity reranker.
 * Selects L items from candidate set C maximizing det(K_Y).
 * K_ij = alpha * rel_i * rel_j - beta * sim(i, j)
 * where rel in [0,1], sim in [0,1].
 *
 * Lightweight, no external deps. Greedy MAP approx.
 */
public class DppDiversityReranker {
    public static class Item {
        public final String id;
        public final double rel; // [0,1]
        public final double[] emb; // normalized vector
        public Item(String id, double rel, double[] emb) {
            this.id = id; this.rel = clamp(rel, 0, 1); this.emb = emb;
        }
    }
    public static class Result {
        public final List<Item> selected;
        public Result(List<Item> selected) { this.selected = selected; }
    }
    private final double alpha;
    private final double beta;
    private final int topK;
    public DppDiversityReranker(double alpha, double beta, int topK) {
        this.alpha = alpha;
        this.beta = beta;
        this.topK = topK;
    }
    public Result rerank(List<Item> items) {
        if (items == null || items.isEmpty()) return new Result(Collections.emptyList());
        int L = Math.min(topK, items.size());
        List<Item> selected = new ArrayList<>(L);
        Set<String> used = new HashSet<>();
        // greedy: at each step pick item maximizing marginal gain
        for (int i = 0; i < L; i++) {
            double bestScore = -1e9;
            Item best = null;
            for (Item it : items) {
                if (used.contains(it.id)) continue;
                double gain = alpha * it.rel * it.rel; // self contribution
                for (Item s : selected) {
                    gain -= beta * cosine(it.emb, s.emb);
                }
                if (gain > bestScore) { bestScore = gain; best = it; }
            }
            if (best == null) break;
            selected.add(best);
            used.add(best.id);
        }
        return new Result(selected);
    }
    private static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot=0; for (int i=0;i<a.length;i++) dot += a[i]*b[i];
        return clamp(dot, -1, 1) * 0.5 + 0.5; // map [-1,1]→[0,1]
    }
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
