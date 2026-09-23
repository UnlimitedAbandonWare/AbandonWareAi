package com.example.lms.dto.agent;

public record OverdriveStatusDto(
        boolean activated,
        double score,
        double threshold,
        boolean aggressive,
        String reason,
        int candidateCount,
        double sparseScore,
        double authorityAvg,
        double contradictionMean,
        double errorRate,
        Object blackboxRiskScore,
        Object blackboxRestoreAction) {
}
