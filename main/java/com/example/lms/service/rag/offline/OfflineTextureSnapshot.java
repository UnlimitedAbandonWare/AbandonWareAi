package com.example.lms.service.rag.offline;

import java.util.List;
import java.util.Map;

public record OfflineTextureSnapshot(
        String snapshotId,
        int schemaVersion,
        String domain,
        String createdAt,
        String expiresAt,
        List<String> anchors,
        List<String> entityKeys,
        List<String> chunkIds,
        Map<String, Double> routePriors,
        Map<String, Double> sourcePriors,
        Map<String, Object> kgAxis,
        Map<String, Object> fusionStats,
        List<String> failureSignatures
) {
    public OfflineTextureSnapshot {
        snapshotId = snapshotId == null ? "" : snapshotId;
        domain = domain == null || domain.isBlank() ? "GENERAL" : domain;
        createdAt = createdAt == null ? "" : createdAt;
        expiresAt = expiresAt == null ? "" : expiresAt;
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
        entityKeys = entityKeys == null ? List.of() : List.copyOf(entityKeys);
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
        routePriors = routePriors == null ? Map.of() : Map.copyOf(routePriors);
        sourcePriors = sourcePriors == null ? Map.of() : Map.copyOf(sourcePriors);
        kgAxis = kgAxis == null ? Map.of() : Map.copyOf(kgAxis);
        fusionStats = fusionStats == null ? Map.of() : Map.copyOf(fusionStats);
        failureSignatures = failureSignatures == null ? List.of() : List.copyOf(failureSignatures);
    }
}
