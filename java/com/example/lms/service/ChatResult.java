package com.example.lms.service;

import java.util.Set;

/**
 * Controller ↔ Service stable response.
 * <p>
 * Extracted from ChatService to break circular dependency between
 * ChatService and ChatWorkflow.
 */
public record ChatResult(String content, String modelUsed, boolean ragUsed, Set<String> evidence) {

    public static ChatResult of(String content, String modelUsed, boolean ragUsed) {
        return new ChatResult(content, modelUsed, ragUsed, Set.of());
    }

    public static ChatResult of(String content, String modelUsed, boolean ragUsed, Set<String> evidence) {
        return new ChatResult(content, modelUsed, ragUsed, evidence == null ? Set.of() : evidence);
    }
}
