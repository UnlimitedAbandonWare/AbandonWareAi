package com.abandonware.ai.agent.integrations.learning.virtualpoint;

import java.util.*;
public final class VirtualPointService {
    public double[] toVector(Map<String, Double> m) {
        double e = m.getOrDefault("evidence_ratio", 0.0);
        double l = m.getOrDefault("latency_ms", 0.0) / 1000.0;
        double d = m.getOrDefault("source_diversity", 0.0);
        return new double[]{ e, l, d };
    }
}