package com.example.lms.dto.agent;

public record HypernovaFusionDto(
        double webWeight,
        double vectorWeight,
        double memoryWeight,
        double dppLambda,
        int dppDefaultK,
        boolean whiteningEnabled,
        String rerankPipeline) {
}
