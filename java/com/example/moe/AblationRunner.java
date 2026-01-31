
package com.example.moe;

import java.util.*;


import static com.example.moe.TensorOps.*;

/**
 * Simple CLI to demonstrate gates and ablations without external deps.
 */
public class AblationRunner {

    public static void main(String[] args) {
        // Synthetic demo dims
        int m = 2;      // query length
        int dk = 4;     // key dim
        int dv = 4;     // value dim
        int dmodel = 4; // model dim

        double[][] Q = new double[][] {
                {0.5, 0.1, 0.2, 0.2},
                {0.1, 0.7, 0.1, 0.1}
        };
        double[][] H_in = new double[][] {
                {0.0, 0.0, 0.0, 0.0},
                {0.0, 0.0, 0.0, 0.0}
        };

        // Three sources J=3 with different stats
        java.util.Random rnd = new java.util.Random(13);
        List<MultiSourceMoE.Source> sources = new ArrayList<>();
        FeatureCollector fc = new FeatureCollector();

        for (int j = 0; j < 3; j++) {
            double[][] K = randn(3, dk, rnd);
            double[][] V = randn(3, dv, rnd);
            double[][] W = eye(dv, dmodel);
            Map<String,Object> meta = new HashMap<>();
            if (j==0) { meta.put("authority", 1.0); meta.put("novelty", 0.9); meta.put("correctionFactor", 1.1); meta.put("match", 0.3); }
            if (j==1) { meta.put("authority", 0.7); meta.put("novelty", 0.4); meta.put("F", 1.5); meta.put("alignmentScore", 0.6); meta.put("recentness", 0.4); }
            if (j==2) { meta.put("baseWeight", 0.4); meta.put("u", 0.7); meta.put("Fd", 0.9); meta.put("m", 0.1); meta.put("reliability", 0.8); }
            FeatureCollector.Features f = fc.collect(meta);
            sources.add(new MultiSourceMoE.Source(K, V, W, f));
        }

        MultiSourceMoE moe = new MultiSourceMoE();
        moe.topK = 2; moe.tau = 0.7;
        moe.w0 = 0.0; moe.wa = 1.0; moe.wu = 0.6; moe.wf = 0.8; moe.wm = 1.2;
        moe.wExtras = new double[]{0.2, 0.2}; // will map to first two extras if present

        MultiSourceMoE.Output out = moe.forward(Q, H_in, sources);

        System.out.println("Gates g: " + java.util.Arrays.toString(out.gates));
        for (int j = 0; j < out.gates.length; j++) {
            System.out.printf(Locale.ROOT, "  g[%d]=%.4f  Features=%s%n", j, out.gates[j], sources.get(j).feat.toString());
        }

        // Ablation examples:
        System.out.println("\n-- Ablation: remove match (w_m=0) --");
        moe.wm = 0.0;
        MultiSourceMoE.Output out2 = moe.forward(Q, H_in, sources);
        System.out.println("Gates g: " + java.util.Arrays.toString(out2.gates));

        System.out.println("\n-- Ablation: remove novelty (w_u=0) --");
        moe.wu = 0.0; moe.wm = 1.2;
        MultiSourceMoE.Output out3 = moe.forward(Q, H_in, sources);
        System.out.println("Gates g: " + java.util.Arrays.toString(out3.gates));
    }

    private static double[][] eye(int n, int m) {
        double[][] E = new double[n][m];
        for (int i = 0; i < Math.min(n,m); i++) E[i][i] = 1.0;
        return E;
    }
    private static double[][] randn(int n, int m, java.util.Random rnd) {
        double[][] X = new double[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) X[i][j] = rnd.nextGaussian()*0.3;
        return X;
    }
}