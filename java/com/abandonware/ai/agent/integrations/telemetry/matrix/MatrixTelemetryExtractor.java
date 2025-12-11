package com.abandonware.ai.agent.integrations.telemetry.matrix;

import java.util.*;
public final class MatrixTelemetryExtractor {
    public Map<String, Double> extract(Map<String, Object> run) {
        Map<String, Double> m = new HashMap<>();
        if (run == null) return m;
        m.put("evidence_ratio", asD(run.get("evidence_ratio")));
        m.put("latency_ms", asD(run.get("latency_ms")));
        m.put("source_diversity", asD(run.get("source_diversity")));
        return m;
    }
    private double asD(Object o) {
        if (o instanceof Number) return ((Number)o).doubleValue();
        return 0.0;
    }
}