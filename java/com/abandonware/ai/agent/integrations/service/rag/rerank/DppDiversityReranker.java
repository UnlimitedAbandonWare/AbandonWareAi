package com.abandonware.ai.agent.integrations.service.rag.rerank;


import java.util.*;
/**
 * Determinantal Point Process (DPP) greedy MAP selector.
 * Toggle: rerank.dpp.enabled (wire at composition layer)
 * Fallback: if candidate vectors unavailable, falls back to MMR-like selection by cosine over title hash.
 */
public class DppDiversityReranker {
    public static class Item {
        public final String id;
        public final double quality;
        public final double[] vector; // optional; if null ⇒ similarity based on hash
        public Item(String id, double quality, double[] vector){
            this.id = id; this.quality = quality; this.vector = vector;
        }
    }

    public List<String> selectTopK(List<Item> items, int k, double jitter){
        if (items == null || items.isEmpty() || k <= 0) return Collections.emptyList();
        int n = items.size();
        boolean useVec = items.get(0).vector != null;
        List<String> selected = new ArrayList<>();
        boolean[] used = new boolean[n];
        double eps = jitter > 0 ? jitter : 1e-6;

        // Precompute similarity
        double[][] S = new double[n][n];
        for (int i=0;i<n;i++){
            for (int j=i;j<n;j++){
                double sim = useVec ? cosine(items.get(i).vector, items.get(j).vector)
                                    : hashSim(items.get(i).id, items.get(j).id);
                S[i][j]=S[j][i]=sim;
            }
        }
        // Kernel K = q_i q_j S_ij
        double[][] K = new double[n][n];
        for (int i=0;i<n;i++) for (int j=0;j<n;j++) K[i][j] = items.get(i).quality * items.get(j).quality * S[i][j];
        for (int i=0;i<n;i++) K[i][i] += eps;

        // Greedy: pick item maximizing marginal gain ~ quality penalized by similarity with selected
        List<Integer> order = new ArrayList<>();
        for (int t=0;t<Math.min(k, n);t++){
            int best = -1;
            double bestScore = -1e9;
            for (int i=0;i<n;i++){
                if (used[i]) continue;
                double divPenalty = 0.0;
                for (int j=0;j<n;j++){
                    if (!used[j]) continue;
                    divPenalty += S[i][j];
                }
                double gain = items.get(i).quality - 0.5 * divPenalty; // heuristic
                if (gain > bestScore){ bestScore = gain; best = i; }
            }
            if (best == -1) break;
            used[best]=true;
            order.add(best);
        }
        for (int idx: order) selected.add(items.get(idx).id);
        return selected;
    }

    private double cosine(double[] a, double[] b){
        if (a==null||b==null||a.length!=b.length) return 0.0;
        double dot=0, na=0, nb=0;
        for (int i=0;i<a.length;i++){ dot+=a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
        if (na==0||nb==0) return 0.0;
        return dot / (Math.sqrt(na)*Math.sqrt(nb));
    }
    private double hashSim(String a, String b){
        int ha = Math.abs(Objects.hashCode(a)) % 101;
        int hb = Math.abs(Objects.hashCode(b)) % 101;
        return 1.0 - (Math.abs(ha - hb) / 101.0);
    }
}
