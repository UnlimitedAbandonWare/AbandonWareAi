
package com.example.lms.fusion;

import java.util.*;
import java.util.stream.Collectors;



/**
 * GrandFusionModule
 * - : 점수기반(WMP) + 순위기반(RRF) 융합 유틸리티
 * - 외부 구현체에 의존하지 않는 순수 유틸리티로 제공하여 어디서든 호출 가능
 */
public final class GrandFusionModule {

    private GrandFusionModule() {}

    /** Weighted Power Mean (a.k.a. Weighted Mean Power, WMP) */
    public static double wmp(double[] scores, double[] weights, double p) {
        if (scores == null || weights == null || scores.length != weights.length || scores.length == 0) return 0.0;
        double num = 0.0;
        for (int i = 0; i < scores.length; i++) {
            double s = clamp(scores[i], 0.0, 1.0);
            double w = Math.max(0.0, weights[i]);
            num += w * Math.pow(s, p);
        }
        return Math.pow(Math.max(0.0, num), 1.0 / p);
    }

    /** Reciprocal Rank Fusion (RRF) with typical k=60 */
    public static double rrf(int rank, int k) {
        int r = Math.max(1, rank);
        int kk = Math.max(1, k);
        return 1.0 / (kk + r);
    }

    /** Hybrid fusion: alpha * WMP + (1-alpha) * RRFNorm */
    public static double hybrid(double[] scores, double[] weights, double p,
                                Integer rank, int rrfK, double alpha) {
        double a = clamp(alpha, 0.0, 1.0);
        double w = wmp(scores, weights, p);
        double rrf = (rank == null ? 0.0 : rrf(rank.intValue(), rrfK));
        // simple normalization: RRF in (0, ~1/k]
        double rrfNorm = rrf * rrfK; // scale roughly to [0,1]
        return a * w + (1.0 - a) * rrfNorm;
    }

    /** Sort helper using hybrid fusion over candidates */
    public static <T> List<T> sortByHybrid(List<T> items,
                                           java.util.function.ToDoubleFunction<T> bm25Score,
                                           java.util.function.ToDoubleFunction<T> denseScore,
                                           java.util.function.ToIntFunction<T> rankProvider,
                                           double wBm25, double wDense, double p,
                                           int rrfK, double alpha) {
        double[] W = new double[] { wBm25, wDense };
        return items.stream()
                .sorted((a,b) -> {
                    double[] Sa = new double[] { bm25Score.applyAsDouble(a), denseScore.applyAsDouble(a) };
                    double[] Sb = new double[] { bm25Score.applyAsDouble(b), denseScore.applyAsDouble(b) };
                    int ra = rankProvider.applyAsInt(a);
                    int rb = rankProvider.applyAsInt(b);
                    double fa = hybrid(Sa, W, p, ra, rrfK, alpha);
                    double fb = hybrid(Sb, W, p, rb, rrfK, alpha);
                    return -Double.compare(fa, fb);
                })
                .collect(Collectors.toList());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}