package com.example.lms.service;

import com.example.lms.dto.RagEvidenceMetadata;
import java.util.List;
import java.util.Set;

/**
 * Controller ↔ Service stable response.
 * <p>
 * Extracted from ChatService to break circular dependency between
 * ChatService and ChatWorkflow.
 */
public record ChatResult(
        String content,
        String modelUsed,
        boolean ragUsed,
        Set<String> evidence,
        List<RagEvidenceMetadata> evidenceMetadata) {

    public ChatResult {
        evidence = evidence == null ? Set.of() : evidence;
        evidenceMetadata = evidenceMetadata == null ? List.of() : List.copyOf(evidenceMetadata);
    }

    public static ChatResult of(String content, String modelUsed, boolean ragUsed) {
        return new ChatResult(content, modelUsed, ragUsed, Set.of(), List.of());
    }

    public static ChatResult of(String content, String modelUsed, boolean ragUsed, Set<String> evidence) {
        return new ChatResult(content, modelUsed, ragUsed, evidence, List.of());
    }

    public static ChatResult of(
            String content,
            String modelUsed,
            boolean ragUsed,
            Set<String> evidence,
            List<RagEvidenceMetadata> evidenceMetadata) {
        return new ChatResult(content, modelUsed, ragUsed, evidence, evidenceMetadata);
    }
}
