package com.example.lms.learning.virtualpoint;

import java.util.Arrays;
import java.util.Locale;

public class VirtualPoint {
    public final float[] vector;
    public final double riskScore;
    public final double priorityScore;
    public final String dominantFailure;
    public final String restoreAction;
    public final String patternId;
    public final long seenAtMs;

    public VirtualPoint(float[] v) {
        this(v, 0.0d, 0.0d, "none", "observe_only", "", System.currentTimeMillis());
    }

    public VirtualPoint(float[] v,
                        double riskScore,
                        double priorityScore,
                        String dominantFailure,
                        String restoreAction,
                        String patternId,
                        long seenAtMs) {
        this.vector = v == null ? new float[0] : Arrays.copyOf(v, v.length);
        this.riskScore = clamp01(riskScore);
        this.priorityScore = clamp01(priorityScore);
        this.dominantFailure = safeLabel(dominantFailure, "none");
        this.restoreAction = safeLabel(restoreAction, "observe_only");
        this.patternId = safeLabel(patternId, "");
        this.seenAtMs = Math.max(0L, seenAtMs);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }

    private static String safeLabel(String value, String fallback) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            s = fallback == null ? "" : fallback.trim().toLowerCase(Locale.ROOT);
        }
        s = s.replaceAll("[^a-z0-9_.:-]+", "_");
        if (s.length() > 96) {
            s = s.substring(0, 96);
        }
        return s.isBlank() ? "none" : s;
    }
}
