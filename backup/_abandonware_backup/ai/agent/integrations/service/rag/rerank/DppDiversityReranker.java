package com.abandonware.ai.agent.integrations.service.rag.rerank;

import java.util.*;
import java.util.function.Function;

/**
 * Diversity reranker upgraded to MMR (Maximal Marginal Relevance).
 * Backward-compatible select(List<T> in, int k) retained; new overload accepts text extractor and lambda.
 */
public class DppDiversityReranker {

    public <T> List<T> select(List<T> in, int k){
        // keep old behavior as a safe fallback: simple head-k with null-guards
        if (in == null || in.isEmpty() || k <= 0) return Collections.emptyList();
        k = Math.min(k, in.size());
        return new ArrayList<>(in.subList(0, k));
    }

    public <T> List<T> select(List<T> in, int k, Function<T,String> textOf, double lambda){
        if (in == null || in.isEmpty() || k <= 0) return Collections.emptyList();
        if (textOf == null) textOf = Objects::toString;
        if (lambda < 0) lambda = 0; if (lambda > 1) lambda = 1;
        k = Math.min(k, in.size());
        List<T> chosen = new ArrayList<>();
        chosen.add(in.get(0));

        while (chosen.size() < k){
            T best = null; double bestScore = -1e9;
            for (T cand : in){
                if (chosen.contains(cand)) continue;
                double rel = relevance(cand); // placeholder: 0..1 (can be overridden by upstream)
                double simMax = 0;
                for (T sel : chosen){
                    simMax = Math.max(simMax, similarity(textOf.apply(cand), textOf.apply(sel)));
                }
                double mmr = lambda*rel - (1.0 - lambda)*simMax;
                if (mmr > bestScore){ bestScore = mmr; best = cand; }
            }
            if (best == null) break;
            chosen.add(best);
        }
        return chosen;
    }

    // --- utilities ---
    protected <T> double relevance(T item){
        // try reflection hook for getScore()/score() else neutral 0.5
        try {
            Object v = item.getClass().getMethod("getScore").invoke(item);
            if (v instanceof Number) return Math.max(0, Math.min(1, ((Number)v).doubleValue()));
        } catch (Throwable ignore){}
        try {
            Object v = item.getClass().getMethod("score").invoke(item);
            if (v instanceof Number) return Math.max(0, Math.min(1, ((Number)v).doubleValue()));
        } catch (Throwable ignore){}
        return 0.5;
    }

    protected double similarity(String a, String b){
        if (a == null || b == null) return 0;
        Set<String> sa = shingles(a, 3), sb = shingles(b, 3);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        int inter = 0;
        for (String x : sa) if (sb.contains(x)) inter++;
        return inter * 1.0 / Math.sqrt(sa.size() * sb.size());
    }

    protected Set<String> shingles(String s, int n){
        Set<String> set = new HashSet<>();
        if (s == null) return set;
        s = s.toLowerCase(Locale.ROOT);
        for (int i=0;i<=s.length()-n;i++) set.add(s.substring(i,i+n));
        return set;
    }
}
