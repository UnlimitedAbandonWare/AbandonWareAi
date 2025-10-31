package com.example.lms.learning.gemini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.lms.learning.gemini.GeminiClient;
import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.MemorySnippet;
import com.example.lms.service.EmbeddingStoreManager;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.List;


/**
 * Service responsible for invoking the Gemini client and applying the resulting
 * knowledge delta to the knowledge base and memory/vector stores. All operations
 * are best-effort; failures are logged but do not propagate back to the caller.
 */
@Service
@RequiredArgsConstructor
public class GeminiCurationService {
    private static final Logger log = LoggerFactory.getLogger(GeminiCurationService.class);

    private final GeminiClient geminiClient;
    private final KnowledgeBaseService knowledgeBase;
    private final EmbeddingStoreManager embeddingStore;
    private final MemoryReinforcementService memorySvc;

    /**
     * Process a learning event through the Gemini curation pipeline.
     *
     * @param event the learning event encapsulating user query, answer and evidence
     * @return the knowledge delta produced by Gemini, or an empty delta if none
     */
    public KnowledgeDelta ingest(LearningEvent event) {
        if (event == null) {
            return new KnowledgeDelta(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        KnowledgeDelta delta;
        try {
            // Model selection can be read from configuration; adjust as needed.
            delta = geminiClient.curate(event, "gemini-2.5-pro", Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("Gemini curation failed: {}", e.toString());
            delta = new KnowledgeDelta(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        // Apply knowledge delta to the knowledge base
        try {
            if (delta != null) {
                knowledgeBase.apply(delta);
                embeddingStore.index(delta.memories());
                // memory reinforcement can be based on memory snippets
                for (MemorySnippet ms : delta.memories()) {
                    try {
                        memorySvc.reinforceWithSnippet(
                                event.sessionId(),
                                event.userQuery(),
                                ms.text(),
                                "ASSISTANT",
                                Math.max(0.0, Math.min(1.0, ms.confidence()))
                        );
                    } catch (Throwable t) {
                        // ignore reinforcement errors
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Applying knowledge delta failed: {}", e.toString());
        }
        return delta;
    }
}