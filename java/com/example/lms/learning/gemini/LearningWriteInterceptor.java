package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.service.MemoryReinforcementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Intercepts the chat pipeline after fact verification to trigger knowledge
 * curation and reinforcement before writing to long-term memory. This class
 * invokes Gemini curation through the GeminiCurationService and falls back
 * to memory reinforcement in case the curation yields no delta.
 */
@Component
@RequiredArgsConstructor
public class LearningWriteInterceptor {
    private static final Logger log = LoggerFactory.getLogger(LearningWriteInterceptor.class);

    private final GeminiCurationService curationService;
    private final MemoryReinforcementService memorySvc;

    /**
     * Ingest a chat turn for learning. The session identifier and user query
     * are passed along to associate the new knowledge with the correct session.
     *
     * @param sessionId session key used in ChatService (e.g. "chat-123")
     * @param query     the user's question
     * @param answer    the final verified answer
     * @param score     confidence score for memory reinforcement (0.0 ~ 1.0)
     */
    public void ingest(String sessionId, String query, String answer, double score) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        try {
            LearningEvent event = new LearningEvent(
                    sessionId == null ? "" : sessionId,
                    query == null ? "" : query,
                    answer,
                    java.util.List.of(),
                    java.util.List.of(),
                    0.0,
                    0.0
            );
            // First, attempt to curate and integrate structured knowledge
            curationService.ingest(event);
        } catch (Exception e) {
            log.debug("Learning ingestion failed: {}", e.toString());
        }
        // Always reinforce with the raw answer to maintain long-term memory
        try {
            memorySvc.reinforceWithSnippet(
                    sessionId,
                    query,
                    answer,
                    "ASSISTANT",
                    Math.max(0.0, Math.min(1.0, score))
            );
        } catch (Throwable t) {
            log.debug("Memory reinforcement failed: {}", t.toString());
        }
    }
}