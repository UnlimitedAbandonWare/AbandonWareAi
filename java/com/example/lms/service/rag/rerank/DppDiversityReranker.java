package com.example.lms.service.rag.rerank;

import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.*;
import java.util.function.Function;

/**
 * Greedy DPP-like diversity reranker with optional relevance blending.
 * Selection objective ~ lambda * base_score + (1-lambda) * novelty_gain,
 * where novelty_gain ~= log(1 + ||P_perp v_i||^2).
 */
public final class DppDiversityReranker {

    public static final class Config {
        public final double lambda;  // [0,1]
        public final int topK;
        public Config(double lambda, int topK) {
            this.lambda = Math.max(0.0, Math.min(1.0, lambda));
            this.topK = Math.max(1, topK);
        }
    }

    private final Config cfg;
    private final EmbeddingModel embeddingModel; // may be null

    public DppDiversityReranker(Config cfg, EmbeddingModel embeddingModel) {
        this.cfg = cfg;
        this.embeddingModel = embeddingModel;
    }

    /** Rerank orchestrator.Doc items. */
    public List<Doc> rerank(List<Doc> candidates, String query, int topK) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        final int K = Math.max(1, Math.min(topK > 0 ? topK : cfg.topK, candidates.size()));

        // Build vectors: prefer provided embeddings in meta -> model -> hashing trick
        float[] qv = embedOrHash(query);
        List<float[]> X = new ArrayList<>(candidates.size());
        for (Doc d : candidates) {
            Object ev = (d.meta == null) ? null : d.meta.get("embedding");
            if (ev instanceof float[]) {
                X.add((float[]) ev);
            } else {
                String text = (d.title == null ? "" : d.title) + " " + (d.snippet == null ? "" : d.snippet);
                X.add(embedOrHash(text));
            }
        }

        // Normalize vectors
        for (int i=0;i<X.size();i++) {
            float[] v = X.get(i);
            double n = 0;
            for (float a : v) n += (double)a*a;
            n = Math.sqrt(n) + 1e-12;
            for (int j=0;j<v.length;j++) v[j] = (float)(v[j]/n);
        }

        // Greedy selection
        boolean[] used = new boolean[candidates.size()];
        List<Integer> sel = new ArrayList<>(K);
        List<float[]> basis = new ArrayList<>();
        double lambda = cfg.lambda;

        // Seed: best relevance if qv available, else best score
        int seed = 0;
        double best = -1e9;
        for (int i=0;i<candidates.size();i++) {
            double rel = (qv == null) ? candidates.get(i).score : dot(qv, X.get(i));
            if (rel > best) { best = rel; seed = i; }
        }
        used[seed] = true; sel.add(seed);
        basis.add(Arrays.copyOf(X.get(seed), X.get(seed).length));

        while (sel.size() < K) {
            int argmax = -1;
            double bestGain = -1e18;
            for (int i=0;i<candidates.size();i++) {
                if (used[i]) continue;
                double novelty = noveltyGain(X.get(i), basis);
                double rel = (qv == null) ? candidates.get(i).score : dot(qv, X.get(i));
                double gain = lambda * rel + (1.0 - lambda) * Math.log1p(novelty);
                if (gain > bestGain) { bestGain = gain; argmax = i; }
            }
            if (argmax < 0) break;
            used[argmax] = true; sel.add(argmax);
            basis.add(Arrays.copyOf(X.get(argmax), X.get(argmax).length));
        }

        List<Doc> out = new ArrayList<>(sel.size());
        for (int idx : sel) out.add(candidates.get(idx));
        return out;
    }

    private float[] embedOrHash(String text) {
        if (embeddingModel != null && text != null && !text.isBlank()) {
            try {
                Response<Embedding> r = embeddingModel.embed(TextSegment.from(text));
                if (r != null && r.content() != null) return r.content().vector();
            } catch (Throwable ignore) {}
        }
        // Fast hashing trick to 256 dims
        int D = 256;
        float[] v = new float[D];
        if (text == null) return v;
        for (int i=0;i<text.length();i++) {
            int h = java.util.Objects.hash(i, text.charAt(i));
            int pos = Math.floorMod(h, D);
            v[pos] += 1.0f;
        }
        return v;
    }

    private static double dot(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0; for (int i=0;i<n;i++) s += (double)a[i]*b[i];
        return s;
    }

    /** novelty ~ ||P_perp v||^2 using Gram-Schmidt projection length. */
    private static double noveltyGain(float[] v, List<float[]> basis) {
        double[] x = new double[v.length];
        for (int i=0;i<v.length;i++) x[i] = v[i];
        for (float[] u : basis) {
            double du = 0, uu = 0;
            int n = Math.min(u.length, v.length);
            for (int i=0;i<n;i++) { du += x[i]*u[i]; uu += (double)u[i]*u[i]; }
            double alpha = (uu <= 1e-12) ? 0.0 : (du / uu);
            for (int i=0;i<n;i++) x[i] -= alpha * u[i];
        }
        double nrm = 0;
        for (double t : x) nrm += t*t;
        return nrm;
    }
}
