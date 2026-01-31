
package com.example.moe;

import java.util.*;


import static com.example.moe.TensorOps.*;

/**
 * MultiSourceMoE: transformer-style cross-attention over multiple sources
 * with a softmax gate whose logits absorb existing signals (authority, novelty, F(d), match, extras).
 *
 * Math:
 *  Attn_j(Q,K_j,V_j) = softmax(QK_j^T / sqrt(d_k) + B_j) V_j
 *  r_j = w0 + wa log a_j + wu log(0.5+0.5 u_j) + wf log F(d_j) + wm m_j + sum_l w_extras[l] xi_{j,l}
 *  g = softmax(r / tau)  [optionally Top-k masked]
 *  C = sum_j g_j W_j Attn_j(/* ... *&#47;)
 *  Residual block with RMSNorm and optional FFN.
 */
public class MultiSourceMoE {

    /** Per-source container. */
    public static class Source {
        public final double[][] K;  // [n_j x d_k]
        public final double[][] V;  // [n_j x d_v]
        public final double[][] W;  // [d_v x d_model] (can be identity)
        public final FeatureCollector.Features feat;
        public Source(double[][] K, double[][] V, double[][] W, FeatureCollector.Features f) {
            this.K = K; this.V = V; this.W = W; this.feat = f;
        }
    }

    /** Output container. */
    public static class Output {
        public final double[][] Hout; // [m x d_model]
        public final double[] gates;  // [J]
        public final double[][][] perSourceProj; // per source projected Attn_j (before mixture)
        public Output(double[][] Hout, double[] gates, double[][][] perSourceProj) {
            this.Hout = Hout; this.gates = gates; this.perSourceProj = perSourceProj;
        }
    }

    // ---- Hyper/weights (tunable) ----
    public double tau = 0.7;     // temperature
    public Integer topK = 2;     // set null to disable

    public double w0 = 0.0;
    public double wa = 1.0;
    public double wu = 0.6;
    public double wf = 0.8;
    public double wm = 1.2;
    public double[] wExtras = new double[]{};

    // Optional FFN params (if null => identity)
    public double[][] W1 = null; public double[] b1 = null;
    public double[][] W2 = null; public double[] b2 = null;

    public MultiSourceMoE() {}

    /** Forward pass.
     * @param Q   [m x d_k] query/context
     * @param H_in [m x d_model] residual input
     * @param sources list of Source
     * @return Output
     */
    public Output forward(double[][] Q, double[][] H_in, List<Source> sources) {
        final int m = Q.length;
        final int dk = (m==0)?0:Q[0].length;
        final int J = sources.size();

        double[][][] perSource = new double[J][][];
        double[] logits = new double[J];

        for (int j = 0; j < J; j++) {
            Source S = sources.get(j);
            // Cross-attention: softmax((Q K^T)/sqrt(dk)) V
            double[][] logitsQK = matmulABt(Q, S.K);
            double scale = 1.0 / Math.sqrt(dk + 1e-12);
            scale(logitsQK, scale);
            double[][] attn = softmaxRows(logitsQK);
            double[][] Cj = matmul(attn, S.V);   // [m x d_v]
            double[][] Pj = matmul(Cj, S.W);     // [m x d_model]
            perSource[j] = Pj;

            // Gate logit r_j with log-domain fusion
            double a = Math.max(S.feat.authority, 1e-12);
            double u = Math.max(0.0, Math.min(1.0, S.feat.novelty));
            double Fd = Math.max(S.feat.Fd, 1e-12);
            double mscore = S.feat.match;
            double r = w0 + wa*Math.log(a) + wu*Math.log(0.5 + 0.5*u) + wf*Math.log(Fd) + wm*mscore;
            if (S.feat.extras != null) {
                int L = Math.min(wExtras.length, S.feat.extras.length);
                for (int t = 0; t < L; t++) r += wExtras[t] * S.feat.extras[t];
            }
            logits[j] = r;
        }

        double[] g = softmax(logits, tau);
        if (topK != null && topK > 0 && topK < g.length) {
            int[] order = argsortDesc(g);
            boolean[] keep = new boolean[g.length];
            for (int t = 0; t < topK; t++) keep[order[t]] = true;
            double sum = 0.0;
            for (int j = 0; j < g.length; j++) { if (!keep[j]) g[j] = 0.0; sum += g[j]; }
            double inv = 1.0 / (sum + 1e-12);
            for (int j = 0; j < g.length; j++) g[j] *= inv;
        }

        // Mixture and residual block
        int dmodel = (perSource[0][0]).length;
        double[][] Hout = new double[m][dmodel];
        for (int i = 0; i < m; i++) {
            double[] mix = weightedSum(perSource, g, i); // [d_model]
            double[] normed = rmsnorm(H_in[i], 1e-5);
            double[] hattn = new double[dmodel];
            for (int k = 0; k < dmodel; k++) hattn[k] = normed[k] + mix[k];

            double[] norm2 = rmsnorm(hattn, 1e-5);
            double[] ff = (W1 == null || W2 == null) ? norm2 : ffn(norm2, W1, b1, W2, b2);

            for (int k = 0; k < dmodel; k++) Hout[i][k] = hattn[k] + ff[k];
        }
        return new Output(Hout, g, perSource);
    }

    // ---- Optional regularizers for ablation/monitoring ----

    /** Novelty loss: encourages larger gates on high-novelty sources. */
    public static double noveltyLoss(double[] g, double[] novelty) {
        double s = 0.0;
        for (int j = 0; j < g.length; j++) {
            double v = Math.log(0.5 + 0.5 * Math.max(0.0, Math.min(1.0, novelty[j])));
            s += - g[j] * v;
        }
        return s;
    }

    /** Coverage loss: penalize very small average gate mass per source. */
    public static double coverageLoss(double[][] Gt, double tau_c) {
        // Gt: [T x J] gate vectors across T steps; here we treat T=1 if not sequential.
        int T = Gt.length, J = (T==0?0:Gt[0].length);
        double[] avg = new double[J];
        for (int t = 0; t < T; t++) for (int j = 0; j < J; j++) avg[j] += Gt[t][j];
        for (int j = 0; j < J; j++) avg[j] /= Math.max(T,1);
        double s = 0.0;
        for (int j = 0; j < J; j++) {
            double gap = tau_c - avg[j];
            if (gap > 0) s += gap;
        }
        return s;
    }

    /** Diversity loss between gate vectors of two heads (or two time steps). */
    public static double diversityLoss(double[] g1, double[] g2) {
        return TensorOps.l2sq(g1, g2);
    }
}