package com.abandonware.ai.agent.nn;

import java.util.*;
import java.util.stream.Collectors;



/**
 * Sigmoid orchestrator for finish/finger variants with random-walk annealing
 * to maximize a "combination rate" signal s in [0,1].
 *
 * finish:  σ(x;k,x0) = 1/(1 + e^{-k(x-x0)})
 * finger:  σ_finger = clip(σ * (1 + α*(2σ-1)), 0, 1)
 *
 * Random walk explores (k, x0) to improve improvementFactor >= 9.0 gate.
 */
public class SigmoidOrchestrator {

    public static class Result {
        public double kBest;
        public double x0Best;
        public double sCombined;
        public double improvementFactor;
        public boolean pass9x;
        public List<double[]> kxHistory = new ArrayList<>(); // [step, k, x0, s]
    }

    private static double finiteOrDefault(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static double positiveFiniteOrDefault(double value, double fallback) {
        double v = finiteOrDefault(value, fallback);
        return v > 0.0 ? v : fallback;
    }

    public static double sigmoidFinish(double x, double k, double x0){
        double safeX = finiteOrDefault(x, 0.0);
        double safeK = positiveFiniteOrDefault(k, 1.0);
        double safeX0 = finiteOrDefault(x0, 0.0);
        return 1.0 / (1.0 + Math.exp(-safeK * (safeX - safeX0)));
    }

    public static double sigmoidFinger(double x, double k, double x0, double alpha){
        double s = sigmoidFinish(x, k, x0);
        double safeAlpha = finiteOrDefault(alpha, 0.0);
        double mod = s * (1.0 + safeAlpha * (2.0 * s - 1.0));
        if (mod < 0) mod = 0;
        if (mod > 1) mod = 1;
        if (!Double.isFinite(mod)) mod = 0;
        return mod;
    }

    /** Weighted sum z = Σ wi*fi, then s = σ*(z; k, x0) */
    public static double combine(double[] features, double[] weights, boolean finger, double alpha, double k, double x0){
        if (features == null || weights == null || features.length == 0) return 0.0;
        double z = 0.0;
        for (int i=0;i<features.length;i++){
            double f = features[i];
            if (!Double.isFinite(f)) {
                continue;
            }
            double w = i < weights.length ? finiteOrDefault(weights[i], 1.0) : 1.0;
            z += w * f;
        }
        if (!Double.isFinite(z)) {
            z = z > 0.0 ? Double.MAX_VALUE : (z < 0.0 ? -Double.MAX_VALUE : 0.0);
        }
        if (finger) return sigmoidFinger(z, k, x0, alpha);
        return sigmoidFinish(z, k, x0);
    }

    /**
     * improvementFactor = (meanExternal * (0.9 + 0.2*s)) / baseline;
     * pass gate if >= 9.0
     */
    public static double improvementFactor(double s, double baseline, List<Double> externalScores){
        if (!Double.isFinite(s)) {
            s = 0.0;
        }
        if (!Double.isFinite(baseline) || baseline <= 0) baseline = 0.000001;
        double m = 0.0;
        if (externalScores != null && !externalScores.isEmpty()){
            m = externalScores.stream()
                    .filter(d -> d != null && Double.isFinite(d))
                    .mapToDouble(d->d)
                    .average()
                    .orElse(0.0);
        }
        return (m * (0.9 + 0.2 * s)) / baseline;
    }

    /**
     * Simple random-walk annealing over (k, x0).
     */
    public Result search(double[] features, double[] weights, boolean finger, double alpha,
                         double baseline, List<Double> externalScores, double k0, double x00,
                         int steps, long seed){
        Random rnd = new Random(seed);
        double k = positiveFiniteOrDefault(k0, 1.0);
        double x0 = finiteOrDefault(x00, 0.0);
        double safeAlpha = finiteOrDefault(alpha, 0.0);
        int safeSteps = Math.max(0, steps);
        Result r = new Result();

        double bestS = -1.0;
        double bestK = k, bestX0 = x0;
        double s = combine(features, weights, finger, safeAlpha, k, x0);
        double imp = improvementFactor(s, baseline, externalScores);
        r.kxHistory.add(new double[]{0, k, x0, s});

        for (int t=1; t<=safeSteps; t++){
            // random perturbation with mild decay
            double dk = (rnd.nextGaussian() * 0.2) / (1.0 + 0.01*t);
            double dx = (rnd.nextGaussian() * 0.2) / (1.0 + 0.01*t);
            double nk = Math.max(0.05, k + dk);  // keep positive slope
            double nx0 = x0 + dx;

            double ns = combine(features, weights, finger, safeAlpha, nk, nx0);
            double nimp = improvementFactor(ns, baseline, externalScores);

            // accept if improves s or occasionally by luck
            boolean accept = ns > s || rnd.nextDouble() < 0.05;
            if (accept){
                k = nk; x0 = nx0; s = ns; imp = nimp;
            }
            if (s > bestS){
                bestS = s; bestK = k; bestX0 = x0;
            }
            r.kxHistory.add(new double[]{t, k, x0, s});
        }

        r.kBest = bestK;
        r.x0Best = bestX0;
        r.sCombined = bestS;
        r.improvementFactor = improvementFactor(bestS, baseline, externalScores);
        r.pass9x = r.improvementFactor >= 9.0;
        return r;
    }
}
