package com.abandonware.ai.addons.complexity;

import java.util.Map;



public record ComplexityResult(
        ComplexityTag tag,
        double confidence,
        Map<String, Object> features
) {}