package com.abandonware.ai.agent.service.rag.rerank;

import java.util.*;

/** Maximal Marginal Relevance selection helper. */
public final class MMRUtil {
    private MMRUtil(){}

    public static List<Integer> select(int k, double lambda, double[] rel, double[][] sim){
        int n = rel.length;
        boolean[] used = new boolean[n];
        List<Integer> S = new ArrayList<>();
        for (int it=0; it<Math.min(k,n); it++){
            int best = -1; double bestScore = -1e18;
            for (int i=0;i<n;i++){
                if (used[i]) continue;
                double red = 0;
                for (int j: S) red = Math.max(red, sim[i][j]);
                double s = lambda*rel[i] - (1.0-lambda)*red;
                if (s > bestScore){ bestScore = s; best=i; }
            }
            if (best<0) break;
            used[best]=true; S.add(best);
        }
        return S;
    }
}