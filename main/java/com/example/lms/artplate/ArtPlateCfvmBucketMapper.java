package com.example.lms.artplate;

import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.search.TraceStore;

final class ArtPlateCfvmBucketMapper {
    private static final double MIN_DOMINANT_WEIGHT = 0.40d;

    private ArtPlateCfvmBucketMapper() {
    }

    static ArtPlateEvolver.PlateFailureBucket detect(RawMatrixBuffer buffer) {
        if (buffer == null) {
            TraceStore.put("moe.rawBuffer.absent", true);
            TraceStore.put("artplate.cfvm.bufferWired", false);
            TraceStore.put("artplate.cfvm.override", false);
            return null;
        }
        TraceStore.put("artplate.cfvm.bufferWired", true);
        double[] weights = buffer.getWeights();
        if (weights == null || weights.length == 0) {
            TraceStore.put("artplate.cfvm.override", false);
            TraceStore.put("artplate.cfvm.overrideReason", "empty_weights");
            return null;
        }

        int maxSlot = 0;
        double maxWeight = finiteWeight(weights[0]);
        for (int i = 1; i < weights.length; i++) {
            double weight = finiteWeight(weights[i]);
            if (weight > maxWeight) {
                maxWeight = weight;
                maxSlot = i;
            }
        }

        TraceStore.put("artplate.cfvm.maxSlot", maxSlot);
        TraceStore.put("artplate.cfvm.maxWeight", maxWeight);
        if (maxWeight < MIN_DOMINANT_WEIGHT) {
            TraceStore.put("artplate.cfvm.override", false);
            TraceStore.put("artplate.cfvm.overrideReason", "signal_weak");
            return null;
        }

        ArtPlateEvolver.PlateFailureBucket bucket = bucketForSlot(maxSlot);
        TraceStore.put("artplate.cfvm.bucket", bucket == null ? "normal" : bucket.name());
        TraceStore.put("artplate.cfvm.override", bucket != null);
        if (bucket == null) {
            TraceStore.put("artplate.cfvm.overrideReason", "normal_slot");
        }
        return bucket;
    }

    private static ArtPlateEvolver.PlateFailureBucket bucketForSlot(int slot) {
        if (slot <= 1) {
            return ArtPlateEvolver.PlateFailureBucket.NO_EVIDENCE;
        }
        if (slot <= 3) {
            return ArtPlateEvolver.PlateFailureBucket.LOW_AUTHORITY;
        }
        if (slot <= 5) {
            return ArtPlateEvolver.PlateFailureBucket.TIMEOUT;
        }
        if (slot <= 7) {
            return ArtPlateEvolver.PlateFailureBucket.CONTRADICTION;
        }
        return null;
    }

    private static double finiteWeight(double value) {
        return Double.isFinite(value) ? Math.max(0.0d, value) : 0.0d;
    }
}
