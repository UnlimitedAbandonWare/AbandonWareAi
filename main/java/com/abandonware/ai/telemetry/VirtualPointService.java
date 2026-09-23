package com.abandonware.ai.telemetry;

import java.util.*;

public class VirtualPointService {
    public double[] compress(Map<String, Object> matrices) {
        // very simple compression to numeric vector (mean-like)
        double a = toD(matrices.get("M2_authority"));
        double r = toD(matrices.get("M5_rerankStrength"));
        double l = toD(matrices.get("M8_cost_latency"));
        return new double[] { a, r, l };
    }
    private double toD(Object o) {
        if (o instanceof Number) return ((Number)o).doubleValue();
        return 0.0;
    }
}