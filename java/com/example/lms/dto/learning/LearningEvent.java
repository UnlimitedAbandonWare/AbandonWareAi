package com.example.lms.dto.learning;

import java.util.List;
import java.util.Objects;



/**
 * Event emitted after fact verification capturing the final answer and its supporting evidence.
 * This event is consumed by the Gemini curation pipeline to produce a structured knowledge delta.
 */
public record LearningEvent(
        String sessionId,
        String userQuery,
        String finalizedAnswer,
        List<EvidenceSnippet> evidence,
        List<ClaimVerdict> claims,
        double coverage,
        double contradiction
) {
    public LearningEvent {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userQuery, "userQuery must not be null");
        Objects.requireNonNull(finalizedAnswer, "finalizedAnswer must not be null");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        claims   = claims == null   ? List.of() : List.copyOf(claims);
    }
}