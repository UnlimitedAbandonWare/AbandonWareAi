package com.example.lms.service.rag.stats;

import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.decomposition.svd.SafeSvd_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Low-rank ZCA whitening stats with Frequent Directions sketch.
 * Query-only transform; index embeddings remain unchanged (backward compatible).
 */
@Slf4j
@Service
public class LowRankWhiteningStats {

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private final AtomicLong seen = new AtomicLong(0);

    @org.springframework.beans.factory.annotation.Value("${rag.whiten.rank:64}")
    private int rank;
    @org.springframework.beans.factory.annotation.Value("${rag.whiten.sketchRows:128}")
    private int sketchRows;
    @org.springframework.beans.factory.annotation.Value("${rag.whiten.eps:1.0E-6}")
    private double eps;

    private int d = -1;
    private float[] mean;                 // Welford mean
    private double[][] B;                 // FD sketch (ℓ x d)
    private int nextRow = 0;

    // Projection materials
    private volatile float[][] Vr;        // d x r
    private volatile float[] invSqrtLam;  // r
    private volatile float alpha = 1.0f;  // 1/sqrt(avg tail variance)

    public void observe(Embedding e) {
        float[] v = e.vector();
        if (v == null || v.length == 0) return;

        rw.writeLock().lock();
        try {
            if (d < 0) {
                d = v.length;
                mean = new float[d];
                if (sketchRows <= 0) sketchRows = Math.max(64, Math.min(256, 2 * Math.max(32, rank)));
                B = new double[sketchRows][d];
            }
            long n0 = seen.getAndIncrement();
            double k = n0 + 1.0;
            for (int i = 0; i < d; i++) {
                mean[i] += (v[i] - mean[i]) / (float)k;
            }
            if (nextRow >= sketchRows) {
                shrink();
                nextRow = countNonZeroRow();
            }
            double[] row = B[nextRow++];
            for (int i = 0; i < d; i++) row[i] = v[i] - mean[i];
        } finally {
            rw.writeLock().unlock();
        }
    }

    public void refit() {
        rw.writeLock().lock();
        try {
            if (d <= 0 || seen.get() < Math.max(200, 3L * rank)) return;
            shrink();
            DMatrixRMaj M = new DMatrixRMaj(sketchRows, d);
            for (int r = 0; r < sketchRows; r++) {
                double[] src = B[r];
                for (int c = 0; c < d; c++) M.set(r, c, src[c]);
            }
            var svd = new SafeSvd_DDRM(DecompositionFactory_DDRM.svd(true, true, true));
            if (!svd.decompose(M)) { log.warn("[Whiten] SVD failed"); return; }

            var V = svd.getV(null, true);
            var S = svd.getSingularValues();
            int r = Math.min(rank, Math.min(S.length, d));
            if (r <= 0) return;
            if (Vr == null || Vr.length != d || Vr[0].length != r) Vr = new float[d][r];
            if (invSqrtLam == null || invSqrtLam.length != r) invSqrtLam = new float[r];

            double nEff = Math.max(1.0, seen.get());
            double tailSum = 0.0; int tailCnt = 0;
            for (int i = 0; i < r; i++) {
                double lam = (S[i] * S[i]) / nEff;
                invSqrtLam[i] = (float)(1.0 / Math.sqrt(lam + eps));
            }
            for (int i = r; i < S.length; i++) {
                tailSum += (S[i] * S[i]) / nEff;
                tailCnt++;
            }
            double tailMean = (tailCnt > 0) ? tailSum / tailCnt : (r > 0 ? (S[Math.min(r, S.length)-1]*S[Math.min(r, S.length)-1])/nEff : 1.0);
            alpha = (float)(1.0 / Math.sqrt(Math.max(tailMean, eps)));

            for (int col = 0; col < r; col++) {
                for (int row = 0; row < d; row++) {
                    Vr[row][col] = (float) V.get(row, col);
                }
            }
            log.info("[Whiten] refit ok: d={} r={} seen={} alpha={}", d, r, seen.get(), String.format("%.3e", alpha));
        } finally {
            rw.writeLock().unlock();
        }
    }

    /** y = α*(x-μ) + V_r * ((diag(invSqrtLam)-αI)*(V_r^T (x-μ))) */
    public float[] transform(float[] x) {
        float[][] V = Vr;
        if (V == null || x == null || d < 0 || x.length != d) return x;
        float[] xc = new float[d];
        for (int i = 0; i < d; i++) xc[i] = x[i] - mean[i];

        int r = V[0].length;
        float[] t = new float[r];
        for (int j = 0; j < r; j++) {
            float acc = 0f;
            for (int i = 0; i < d; i++) acc += V[i][j] * xc[i];
            t[j] = (invSqrtLam[j] - alpha) * acc;
        }
        float[] y = new float[d];
        for (int i = 0; i < d; i++) {
            float acc = 0f;
            for (int j = 0; j < r; j++) acc += V[i][j] * t[j];
            y[i] = alpha * xc[i] + acc;
        }
        return y;
    }

    private void shrink() {
        if (B == null) return;
        int rows = B.length;
        int cols = (d > 0 ? d : 0);
        if (rows == 0 || cols == 0) return;

        DMatrixRMaj M = new DMatrixRMaj(rows, cols);
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) M.set(r, c, B[r][c]);

        var svd = new SafeSvd_DDRM(DecompositionFactory_DDRM.svd(true, true, true));
        if (!svd.decompose(M)) return;

        var S = svd.getSingularValues();
        var VT = svd.getV(null, true);

        int half = rows / 2;
        int keep = Math.min(half, Math.min(S.length, cols));
        double delta = (keep > 0) ? (S[keep-1] * S[keep-1]) : 0.0;

        double[] sprime = new double[keep];
        for (int i = 0; i < keep; i++) {
            double v = S[i]*S[i] - delta;
            sprime[i] = v > 0 ? Math.sqrt(v) : 0.0;
        }
        // create new compacted B
        double[][] newB = new double[rows][cols];
        for (int i = 0; i < keep; i++) {
            for (int c = 0; c < cols; c++) newB[i][c] = sprime[i] * VT.get(i, c);
        }
        B = newB;
        nextRow = keep;
    }

    private int countNonZeroRow() {
        int rows = B.length, cols = d;
        int r = 0;
        for (; r < rows; r++) {
            double[] row = B[r];
            boolean zero = true;
            for (int c = 0; c < cols; c++) if (row[c] != 0.0) { zero = false; break; }
            if (zero) break;
        }
        return r;
    }
}
