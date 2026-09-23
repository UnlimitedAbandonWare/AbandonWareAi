package com.example.lms.service.rag.graph;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record InferenceResult(
        boolean enabled,
        String queryHash,
        String mode,
        List<String> matchedEntities,
        List<String> inferredRelations,
        Map<String, Object> ragDebug,
        String disabledReason,
        Instant capturedAt) {

    public InferenceResult {
        matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
        inferredRelations = inferredRelations == null ? List.of() : List.copyOf(inferredRelations);
        ragDebug = ragDebug == null ? Map.of() : Map.copyOf(ragDebug);
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }
}
