package com.abandonware.ai.service.rag.fusion;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MinMaxCalibrator implements ScoreCalibrator {

    private final Map<String, double[]> stats = new ConcurrentHashMap<>();

    @Override
    public double normalize(double raw, String source) {
        String key = source == null ? "default" : source;
        double[] st = stats.computeIfAbsent(key, k -> new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY});
        // update min/max (online)
        if (raw < st[0]) st[0] = raw;
        if (raw > st[1]) st[1] = raw;
        double min = st[0], max = st[1];
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) return 0.0;
        double x = (raw - min) / (max - min);
        if (x < 0) x = 0; if (x > 1) x = 1;
        return x;
    }
}