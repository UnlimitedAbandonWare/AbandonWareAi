package com.abandonware.patch.fusion;

import java.util.Arrays;

public class CvarAggregator {
    public double cvar(double[] scores, double alpha) {
        if (scores == null || scores.length == 0) return 0.0;
        double[] sorted = Arrays.copyOf(scores, scores.length);
        Arrays.sort(sorted);
        int start = (int) Math.floor((1.0 - Math.max(0.01, Math.min(0.5, alpha))) * sorted.length);
        start = Math.max(0, Math.min(sorted.length - 1, start));
        double sum = 0.0; int cnt = 0;
        for (int i = start; i < sorted.length; i++) { sum += sorted[i]; cnt++; }
        return cnt == 0 ? 0.0 : sum / cnt;
    }
}