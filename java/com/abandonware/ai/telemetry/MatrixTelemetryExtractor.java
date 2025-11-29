package com.abandonware.ai.telemetry;

import java.util.*;

public class MatrixTelemetryExtractor {
    public Map<String, Object> extract(Map<String, Object> run) {
        Map<String, Object> m = new HashMap<>();
        m.put("M1_sources", run.getOrDefault("sources", List.of()));
        m.put("M2_authority", run.getOrDefault("authority", 0.0));
        m.put("M5_rerankStrength", run.getOrDefault("rerankStrength", 0.0));
        m.put("M8_cost_latency", run.getOrDefault("latencyMs", 0));
        return m;
    }
}