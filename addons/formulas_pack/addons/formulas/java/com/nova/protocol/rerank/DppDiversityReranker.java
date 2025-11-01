package com.nova.protocol.rerank;

import java.util.ArrayList;
import java.util.List;

/**
 * [GPT-PRO-AGENT v2] — concise navigation header (no runtime effect).
 * Module: com.nova.protocol.rerank.DppDiversityReranker
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.rerank.DppDiversityReranker
role: config
*/
public class DppDiversityReranker {

    public List<Integer> rerank(List<Double> relevance, double[][] similarity, int k, double lambda) {
        int n = relevance.size();
        boolean[] used = new boolean[n];
        List<Integer> pick = new ArrayList<>();

        for (int step = 0; step < k && step < n; step++) {
            int best = -1; double bestScore = -1e18;
            for (int i = 0; i < n; i++) if (!used[i]) {
                double divPenalty = 0.0;
                for (int j : pick) divPenalty += similarity[i][j];
                double score = lambda * relevance.get(i) - (1.0 - lambda) * divPenalty;
                if (score > bestScore) { bestScore = score; best = i; }
            }
            if (best >= 0) { used[best] = true; pick.add(best); } else break;
        }
        return pick;
    }
}