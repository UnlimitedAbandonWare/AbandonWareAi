package com.example.lms.service.rag.rerank;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.stream.Collectors;

/** Greedy DPP-inspired reranker (log-det gain approx). Fail-soft to base. */
@RequiredArgsConstructor
public class DppDiversityReranker implements CrossEncoderReranker {

    private final CrossEncoderReranker base;
    private final EmbeddingModel embeddingModel;
    // Optionally injected cache for memoizing embeddings. When present, reranker uses cache to avoid duplicate embedding calls.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.rag.cache.EmbeddingCache embeddingCache;

    @org.springframework.beans.factory.annotation.Value("${rag.diversity.k:32}")
    private int K;
    @org.springframework.beans.factory.annotation.Value("${rag.diversity.lambda:0.7}")
    private double lambda; // relevance vs diversity mixing

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        List<Content> ranked = base.rerank(query, candidates, Math.max(topN, candidates.size()));
        if (ranked.size() <= 2) return ranked;

        int k = Math.min(K, ranked.size());
        List<Content> pool = ranked.subList(0, k);
        List<TextSegment> segs = pool.stream().map(Content::textSegment).collect(Collectors.toList());
        // Use cache if available; fallback to direct embedding call otherwise (fail‑soft).
        List<Embedding> embs = (embeddingCache != null)
                ? embeddingCache.embedAll(segs)
                : Optional.ofNullable(embeddingModel.embedAll(segs).content()).orElse(List.of());
        if (embs.size() != k) return ranked;

        double[][] S = cosine(embs); // symmetric
        double beta = 3.0;
        double[] q = new double[k];
        for (int i = 0; i < k; i++) {
            double rel = 1.0 - ((double)i / (double)k);
            q[i] = Math.exp(beta * rel);
        }
        // L = diag(q) * S^2 * diag(q)
        double[][] L = new double[k][k];
        for (int i = 0; i < k; i++) {
            for (int j = i; j < k; j++) {
                double v = S[i][j] * S[i][j] * q[i] * q[j];
                L[i][j] = v; L[j][i] = v;
            }
        }

        boolean[] used = new boolean[k];
        List<Integer> chosen = new ArrayList<>();
        List<double[]> basis = new ArrayList<>();

        while (chosen.size() < Math.min(topN, k)) {
            int pick = -1; double best = -1e9;
            for (int i = 0; i < k; i++) if (!used[i]) {
                double rel = 1.0 - ((double)i / (double)k);
                double novelty = 1.0 - projSquared(S, i, chosen, basis);
                double score = lambda * rel + (1.0 - lambda) * Math.log1p(novelty);
                if (score > best) { best = score; pick = i; }
            }
            if (pick < 0) break;
            used[pick] = true; chosen.add(pick);
            basis.add(vectorFrom(S, pick, k));
            orthonormalize(basis);
        }

        List<Content> out = new ArrayList<>();
        for (int id : chosen) out.add(pool.get(id));
        for (int i = 0; i < k && out.size() < topN; i++) if (!used[i]) out.add(pool.get(i));
        return out;
    }

    private static double[][] cosine(List<Embedding> embs) {
        int n = embs.size();
        int d = embs.get(0).vector().length;
        double[][] v = new double[n][d];
        double[] norm = new double[n];
        for (int i = 0; i < n; i++) {
            float[] f = embs.get(i).vector();
            double s = 0;
            for (int j = 0; j < d; j++) { v[i][j] = f[j]; s += f[j]*f[j]; }
            norm[i] = Math.sqrt(Math.max(s, 1e-12));
            for (int j = 0; j < d; j++) v[i][j] /= norm[i];
        }
        double[][] S = new double[n][n];
        for (int i = 0; i < n; i++) {
            S[i][i] = 1.0;
            for (int j = i+1; j < n; j++) {
                double s = 0;
                for (int t = 0; t < d; t++) s += v[i][t]*v[j][t];
                S[i][j] = s; S[j][i] = s;
            }
        }
        return S;
    }

    private static double projSquared(double[][] S, int i, List<Integer> chosen, List<double[]> basis) {
        if (chosen.isEmpty()) return 0.0;
        double maxSq = 0.0;
        for (double[] b : basis) {
            double num = 0, den = 0, denb = 0;
            for (int t = 0; t < b.length; t++) {
                num  += b[t] * S[i][t];
                den  += S[i][t] * S[i][t];
                denb += b[t]   * b[t];
            }
            double cos = (num / (Math.sqrt(Math.max(den,1e-12))*Math.sqrt(Math.max(denb,1e-12))));
            maxSq = Math.max(maxSq, cos*cos);
        }
        return Math.min(1.0, maxSq);
    }

    private static double[] vectorFrom(double[][] S, int row, int n) {
        double[] v = new double[n];
        System.arraycopy(S[row], 0, v, 0, n);
        return v;
    }

    private static void orthonormalize(List<double[]> basis) {
        for (int i = 0; i < basis.size(); i++) {
            double[] vi = basis.get(i);
            for (int j = 0; j < i; j++) {
                double[] vj = basis.get(j);
                double dot = 0, n2 = 0;
                for (int t = 0; t < vi.length; t++) { dot += vi[t]*vj[t]; n2 += vj[t]*vj[t]; }
                double proj = (n2 > 1e-12) ? dot/n2 : 0.0;
                for (int t = 0; t < vi.length; t++) vi[t] -= proj * vj[t];
            }
            double nrm = 0; for (double x : vi) nrm += x*x;
            nrm = Math.sqrt(Math.max(nrm, 1e-12));
            for (int t = 0; t < vi.length; t++) vi[t] /= nrm;
        }
    }
}
