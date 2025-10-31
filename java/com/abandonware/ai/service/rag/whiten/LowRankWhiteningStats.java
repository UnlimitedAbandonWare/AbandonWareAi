package com.abandonware.ai.service.rag.whiten;

import java.util.*;
import java.util.stream.*;

public class LowRankWhiteningStats {
    public final float[] mean;
    public final float[][] eigVecs;
    public final float[] eigVals;
    public final double epsilon;
    public final long fittedAtEpochMs;

    public LowRankWhiteningStats(float[] mean, float[][] eigVecs, float[] eigVals, double epsilon, long fittedAtEpochMs){
        this.mean = mean; this.eigVecs = eigVecs; this.eigVals = eigVals; this.epsilon = epsilon; this.fittedAtEpochMs = fittedAtEpochMs;
    }

    public static LowRankWhiteningStats fromSamples(Stream<float[]> samples, int rank, double eps){
        // Lightweight estimate: mean + identity basis (fail-soft). No external numeric deps.
        java.util.List<float[]> list = samples.limit(2048).collect(Collectors.toList());
        if (list.isEmpty()){
            return new LowRankWhiteningStats(new float[0], new float[0][0], new float[0], eps, System.currentTimeMillis());
        }
        int d = list.get(0).length;
        float[] mean = new float[d];
        for (float[] v : list){
            for (int i=0;i<d;i++) mean[i] += v[i];
        }
        for (int i=0;i<d;i++) mean[i] /= Math.max(1, list.size());
        // Identity eigens (placeholder). A real impl would compute top-r eigenpairs.
        int r = Math.max(1, Math.min(rank, d));
        float[][] V = new float[r][d];
        for (int i=0;i<r;i++){
            V[i] = new float[d];
            if (i<d) V[i][i] = 1.0f;
        }
        float[] L = new float[r];
        java.util.Arrays.fill(L, 1.0f);
        return new LowRankWhiteningStats(mean, V, L, eps, System.currentTimeMillis());
    }
}
