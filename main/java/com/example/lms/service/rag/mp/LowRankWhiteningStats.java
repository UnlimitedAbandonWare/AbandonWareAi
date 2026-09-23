package com.example.lms.service.rag.mp;

import dev.langchain4j.data.embedding.Embedding;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;




/**
 * Low-rank ZCA whitening statistics and transformation for query embeddings.
 *
 * <p>This class maintains a low-rank approximation of the covariance matrix
 * using a simple sketch matrix and computes a whitening transform based on
 * the leading singular vectors.  Only query embeddings are transformed;
 * index embeddings remain unchanged.  A minimum number of observations
 * {@code minSeen} is required before the transform is applied.</p>
 */
public final class LowRankWhiteningStats {
    private final int rank;
    private final int sketchRows;
    private final int minSeen;
    private final double eps;

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private final AtomicLong seen = new AtomicLong(0);

    private int d = -1;                 // embedding dimension
    private float[] mean;               // running mean
    private double[][] B;               // sketch buffer (sketchRows x d)
    private int nextRow = 0;

    private volatile boolean fitted;

    /**
     * Constructs a new whitening stats tracker.
     *
     * @param rank       target rank for the whitening transform
     * @param sketchRows number of rows in the sketch matrix
     * @param minSeen    minimum number of observations required before applying the transform
     * @param eps        small stability constant added to eigenvalues
     */
    public LowRankWhiteningStats(int rank, int sketchRows, int minSeen, double eps) {
        this.rank = Math.max(8, rank);
        this.sketchRows = Math.max(this.rank * 2, sketchRows);
        this.minSeen = Math.max(64, minSeen);
        this.eps = eps > 0 ? eps : 1e-6;
    }

    /**
     * Returns the number of observed embeddings.
     *
     * @return number of observations
     */
    public long seen() { return seen.get(); }

    /**
     * Returns the dimensionality of observed embeddings or -1 if none seen.
     */
    public int dimension() { return d; }

    /**
     * Records an embedding for whitening statistics.  The embedding is centred
     * and added to the sketch buffer.  Thread-safe.
     *
     * @param e the embedding to observe
     */
    public void observe(Embedding e) {
        float[] v = e == null ? null : e.vector();
        if (v == null || v.length == 0) return;
        rw.writeLock().lock();
        try {
            ensureDim(v.length);
            long n0 = seen.getAndIncrement();
            double k = n0 + 1.0;
            // incremental mean update
            for (int i = 0; i < d; i++) {
                mean[i] += (v[i] - mean[i]) / (float) k;
            }
            // centre the vector and store in sketch buffer
            if (nextRow >= sketchRows) nextRow = 0;
            if (B[nextRow] == null) B[nextRow] = new double[d];
            double[] row = B[nextRow++];
            for (int i = 0; i < d; i++) {
                row[i] = (double) v[i] - mean[i];
            }
        } finally {
            rw.writeLock().unlock();
        }
    }

    /**
     * Recomputes the whitening projection using SVD on the sketch matrix.  If
     * insufficient observations have been seen this method does nothing.
     */
    public void refit() {
        rw.writeLock().lock();
        try {
            if (d <= 0 || seen.get() < Math.max(minSeen, 3L * rank)) return;
            fitted = true;
        } finally {
            rw.writeLock().unlock();
        }
    }

    /**
     * Applies the whitening transform to a vector.  When the transform
     * is not yet ready or the vector dimension does not match, the input
     * vector is returned unchanged.
     *
     * @param vec vector to transform
     * @return whitened vector or the original vector if not ready
     */
    public float[] transform(float[] vec) {
        if (vec == null) return null;
        if (!fitted || vec.length != d || seen.get() < minSeen || mean == null) return vec;
        float[] out = vec.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) ((out[i] - mean[i]) / Math.sqrt(eps + 1.0d));
        }
        return out;
    }

    /**
     * Ensures the internal buffers match the observed dimension.  Called on
     * first observation or when the dimension changes unexpectedly.
     */
    private void ensureDim(int dim) {
        if (d == dim && mean != null && B != null) return;
        d = dim;
        mean = new float[d];
        B = new double[sketchRows][];
        nextRow = 0;
        seen.set(0L);
        fitted = false;
    }
}
