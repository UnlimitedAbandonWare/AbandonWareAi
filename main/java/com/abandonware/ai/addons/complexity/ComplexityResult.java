package com.abandonware.ai.addons.complexity;

import java.util.Map;



public record ComplexityResult(
        ComplexityTag tag,
        double confidence,
        Map<String, Object> features
) {
    public ComplexityResult {
        tag = tag == null ? ComplexityTag.SIMPLE : tag;
        confidence = probability(confidence);
        features = features == null ? Map.of() : Map.copyOf(features);
    }

    private static double probability(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
