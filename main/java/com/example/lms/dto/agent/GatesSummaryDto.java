package com.example.lms.dto.agent;

public record GatesSummaryDto(
        double sigmoidThreshold,
        String sigmoidMode,
        boolean aggressiveMode,
        int citationMinCount,
        boolean citationRequireOfficial,
        boolean piiSanitizerActive,
        long gatePassCount,
        long gateBlockCount,
        long gateWarnCount) {
}
