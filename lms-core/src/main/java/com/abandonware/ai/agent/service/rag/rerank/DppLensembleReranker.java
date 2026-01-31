package com.abandonware.ai.agent.service.rag.rerank;

import java.util.*;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.service.rag.rerank.DppLensembleReranker
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.service.rag.rerank.DppLensembleReranker
role: config
*/
public class DppLensembleReranker {

    public static class Item {
        public final int idx;
        public final double relevance;
        public final double[] emb;
        public Item(int idx, double relevance, double[] emb) { this.idx=idx; this.relevance=relevance; this.emb=emb; }
    }

    public List<Integer> select(List<Item> items, int k, double alpha){
        final int n = items.size();
        if (k<=0 || n==0) return Collections.emptyList();
        k = Math.min(k, n);

        // Precompute K = X X^T
        double[][] K = new double[n][n];
        for (int i=0;i<n;i++){
            for (int j=i;j<n;j++){
                double dot=0;
                double[] a = items.get(i).emb, b = items.get(j).emb;
                for (int t=0;t<a.length;t++) dot += a[t]*b[t];
                K[i][j]=K[j][i]=dot;
            }
        }
        // Scale by D^alpha
        double[] d = new double[n];
        for (int i=0;i<n;i++) d[i] = Math.pow(Math.max(1e-9, items.get(i).relevance), alpha);
        for (int i=0;i<n;i++) for (int j=0;j<n;j++) K[i][j] = d[i]*K[i][j]*d[j];

        // Greedy with naive updates (for small k)
        List<Integer> S = new ArrayList<>();
        boolean[] chosen = new boolean[n];
        double eps = 1e-8;

        // Diagonal gains to start
        double[] gains = new double[n];
        for (int i=0;i<n;i++) gains[i] = Math.log(K[i][i] + eps);

        for (int it=0; it<k; it++){
            int best=-1;
            double bestGain = -1e18;
            for (int i=0;i<n;i++){
                if (chosen[i]) continue;
                if (gains[i] > bestGain){
                    bestGain = gains[i]; best=i;
                }
            }
            if (best<0) break;
            chosen[best]=true;
            S.add(items.get(best).idx);

            // Schur complement update (naive O(n^2))
            // K := K - (K[:,b] K[b,:])/(K[b,b]+eps)
            double kb = K[best][best] + eps;
            double[] col = new double[n];
            for (int i=0;i<n;i++) col[i]=K[i][best];
            for (int i=0;i<n;i++){
                for (int j=0;j<n;j++){
                    K[i][j] -= col[i]*col[j]/kb;
                }
            }
            for (int i=0;i<n;i++){
                gains[i] = Math.log(Math.max(K[i][i] + eps, eps));
            }
        }
        return S;
    }
}