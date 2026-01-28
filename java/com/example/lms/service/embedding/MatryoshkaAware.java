package com.example.lms.service.embedding;

/**
 * Matryoshka Representation Learning (MRL) embedding support.
 *
 * Some embedding models may return vectors larger than the configured index
 * dimension. To keep vector search stable, we deterministically slice/truncate
 * vectors to the index dimension (and pad with zeros when needed).
 */
public interface MatryoshkaAware {

    int indexDimensions();

    default float[] sliceVector(float[] raw, int targetDim) {
        if (raw == null) return new float[0];
        if (targetDim <= 0) return raw;
        if (raw.length == targetDim) return raw;
        float[] out = new float[targetDim];
        int n = Math.min(raw.length, targetDim);
        System.arraycopy(raw, 0, out, 0, n);
        return out;
    }

    default double[] sliceVector(double[] raw, int targetDim) {
        if (raw == null) return new double[0];
        if (targetDim <= 0) return raw;
        if (raw.length == targetDim) return raw;
        double[] out = new double[targetDim];
        int n = Math.min(raw.length, targetDim);
        System.arraycopy(raw, 0, out, 0, n);
        return out;
    }
}
