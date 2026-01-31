package com.example.lms.service.rag.rerank;

import java.util.*;
import java.util.function.Function;

/**
 * Drop-in diversity reranker with MMR-like selection.
 * Designed to be resilient to missing dependencies. Constructor accepts varargs for optional deps.
 */
public class DppDiversityReranker {

    public static class Config {
        public final double lambda; // relevance-diversity tradeoff (0..1)
        public final int defaultK;

        public Config(double lambda, int defaultK) {
            this.lambda = Math.max(0.0, Math.min(1.0, lambda));
            this.defaultK = Math.max(1, defaultK);
        }
    }

    private final Config cfg;
    @SuppressWarnings("unused")
    private final Object[] deps;

    public DppDiversityReranker(Config cfg, Object... deps) {
        this.cfg = cfg;
        this.deps = deps;
    }

    /** Generic rerank: in-place safe (returns new list). */
    public <T> List<T> rerank(List<T> in, String query, int k) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        k = Math.min(Math.max(1, k), in.size());
        double lambda = cfg != null ? cfg.lambda : 0.7;

        // Extract text for similarity: try common getters/fields via reflection; fallback to toString()
        Function<T,String> textOf = (T x) -> {
            try {
                // title + snippet fields if exist
                StringBuilder sb = new StringBuilder();
                try { sb.append(String.valueOf(x.getClass().getField("title").get(x))).append(" "); } catch (Throwable ignore) {}
                try { sb.append(String.valueOf(x.getClass().getField("snippet").get(x))).append(" "); } catch (Throwable ignore) {}
                if (sb.length() == 0) {
                    try { sb.append(String.valueOf(x.getClass().getMethod("getTitle").invoke(x))).append(" "); } catch (Throwable ignore) {}
                    try { sb.append(String.valueOf(x.getClass().getMethod("getSnippet").invoke(x))).append(" "); } catch (Throwable ignore) {}
                }
                if (sb.length() == 0) sb.append(String.valueOf(x));
                return sb.toString();
            } catch (Throwable t) {
                return String.valueOf(x);
            }
        };

        // Relevance: basic position prior (higher = better) with light query overlap bonus
        Map<T, Double> rel = new IdentityHashMap<>();
        for (int i=0;i<in.size();i++) {
            T t = in.get(i);
            double base = 1.0 - (i * 1.0 / Math.max(1, in.size()-1)); // 1..0
            double bonus = overlapScore(textOf.apply(t), query);
            rel.put(t, clamp01(0.85*base + 0.15*bonus));
        }

        List<T> chosen = new ArrayList<>();
        chosen.add(in.get(0));
        while (chosen.size() < k) {
            T best = null;
            double bestScore = -1e9;
            for (T cand : in) {
                if (chosen.contains(cand)) continue;
                double relevance = rel.getOrDefault(cand, 0.5);
                double maxSim = 0.0;
                for (T sel : chosen) {
                    double s = textSimilarity(textOf.apply(cand), textOf.apply(sel));
                    if (s > maxSim) maxSim = s;
                }
                double mmr = lambda * relevance - (1.0 - lambda) * maxSim;
                if (mmr > bestScore) { bestScore = mmr; best = cand; }
            }
            if (best == null) break;
            chosen.add(best);
        }
        return chosen;
    }

    /** Convenience select overloads (for compatibility). */
    public <T> List<T> select(List<T> in, int k) {
        return rerank(in, "", k);
    }
    public <T> List<T> select(List<T> in, int k, Function<T,String> textOf, double lambda) {
        // Use provided textOf and lambda; fallback to default for query-less rerank
        if (in == null || in.isEmpty()) return Collections.emptyList();
        k = Math.min(Math.max(1, k), in.size());
        lambda = Math.max(0.0, Math.min(1.0, lambda));
        Map<T, Double> rel = new IdentityHashMap<>();
        for (int i=0;i<in.size();i++) {
            double base = 1.0 - (i * 1.0 / Math.max(1, in.size()-1));
            rel.put(in.get(i), base);
        }
        List<T> chosen = new ArrayList<>();
        chosen.add(in.get(0));
        while (chosen.size() < k) {
            T best = null; double bestScore = -1e9;
            for (T cand : in) {
                if (chosen.contains(cand)) continue;
                double relevance = rel.getOrDefault(cand, 0.5);
                double simMax = 0.0;
                for (T sel : chosen) {
                    double s = textSimilarity(textOf.apply(cand), textOf.apply(sel));
                    if (s > simMax) simMax = s;
                }
                double mmr = lambda * relevance - (1.0 - lambda) * simMax;
                if (mmr > bestScore) { bestScore = mmr; best = cand; }
            }
            if (best == null) break;
            chosen.add(best);
        }
        return chosen;
    }

    private static double textSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        Set<String> A = shingles(a, 3);
        Set<String> B = shingles(b, 3);
        if (A.isEmpty() || B.isEmpty()) return 0.0;
        int inter = 0;
        for (String x : A) if (B.contains(x)) inter++;
        return inter / Math.sqrt((double)A.size() * (double)B.size());
    }

    private static Set<String> shingles(String s, int n) {
        Set<String> set = new HashSet<>();
        if (s == null) return set;
        s = s.toLowerCase(Locale.ROOT);
        for (int i=0;i<=s.length()-n;i++) set.add(s.substring(i, i+n));
        return set;
    }

    private static double overlapScore(String text, String query) {
        if (text == null || query == null || query.isBlank()) return 0.0;
        String[] qs = query.toLowerCase(Locale.ROOT).split("\\s+");
        int hit = 0;
        for (String q : qs) if (text.toLowerCase(Locale.ROOT).contains(q)) hit++;
        return clamp01(hit * 1.0 / Math.max(1, qs.length));
        }

    private static double clamp01(double x){ return Math.max(0.0, Math.min(1.0, x)); }
}