package com.example.lms.dto.agent;

import java.util.Map;

public record CfvmSnapshotDto(
        long currentPatternId,
        String signatureText,
        int bufferSize,
        Map<String, Object> traceKeys) {
}
