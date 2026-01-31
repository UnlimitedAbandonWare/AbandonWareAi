package com.abandonware.ai.rerank;

import java.util.*;

/** ScoreCalibrator - simple monotonic calibration via quantile mapping. */
public class ScoreCalibrator {
    public double calibrate(double raw, List<Double> ref) {
        if (ref==null || ref.isEmpty()) return raw;
        List<Double> r=new ArrayList<>(ref); Collections.sort(r);
        double p = percentile(raw, r);
        // map to [0,1]
        return Math.max(0.0, Math.min(1.0, p));
    }
    private double percentile(double x, List<Double> sorted) {
        int n = sorted.size();
        if (x <= sorted.get(0)) return 0.0;
        if (x >= sorted.get(n-1)) return 1.0;
        for (int i=1;i<n;i++) {
            if (x <= sorted.get(i)) {
                double a = sorted.get(i-1), b = sorted.get(i);
                double t = (x - a) / (b - a + 1e-9);
                return (i-1 + t) / (n-1);
            }
        }
        return 1.0;
    }
}