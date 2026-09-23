package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.MemorySnippet;
import com.example.lms.search.TraceStore;
import com.example.lms.service.EmbeddingStoreManager;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Fail-closed Gemini knowledge curation facade.
 */
@Service
@RequiredArgsConstructor
public class GeminiCurationService {

    private static final Logger log = LoggerFactory.getLogger(GeminiCurationService.class);

    private final GeminiClient geminiClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingStoreManager embeddingStoreManager;
    private final MemoryReinforcementService memoryReinforcementService;

    @Value("${knowledge-curation.enabled:false}")
    private boolean enabled;

    @Value("${knowledge-curation.model-id:${llm.chat-model:${openai.chat.model:gemini-2.5-pro}}}")
    private String modelId;

    @Value("${knowledge-curation.timeout-seconds:30}")
    private long timeoutSeconds;

    @Value("${knowledge-curation.min-confidence:0.5}")
    private double minConfidence;

    public KnowledgeDelta ingest(LearningEvent event) {
        return ingestWithResult(event).delta();
    }

    public CurationResult ingestWithResult(LearningEvent event) {
        if (!enabled) {
            return notApplied("curation_disabled", emptyDelta());
        }
        if (event == null) {
            return notApplied("invalid_event", emptyDelta());
        }

        KnowledgeDelta delta;
        try {
            Duration timeout = Duration.ofSeconds(Math.max(1L, timeoutSeconds));
            String effectiveModel = (modelId == null || modelId.isBlank()) ? "gemini-2.5-pro" : modelId;
            delta = geminiClient.curate(event, effectiveModel, timeout);
        } catch (Exception e) {
            log.warn("Gemini curation failed sessionHash={} errorHash={} errorLength={}",
                    SafeRedactor.hashValue(event.sessionId()),
                    SafeRedactor.hashValue(messageOf(e)),
                    messageLength(e));
            return notApplied("curation_call_failed", emptyDelta());
        }

        if (isEmpty(delta)) {
            return notApplied("curation_empty_or_not_implemented", emptyDelta());
        }

        try {
            knowledgeBaseService.apply(delta);
            embeddingStoreManager.index(delta.memories());

            List<MemorySnippet> memories = delta.memories();
            if (memories == null || memories.isEmpty()) {
                traceResult(true, "", delta);
                return new CurationResult(true, "", delta);
            }

            for (MemorySnippet ms : memories) {
                if (ms == null) continue;
                String text = ms.text();
                if (text == null || text.isBlank()) {
                    continue;
                }
                double conf = Math.max(0.0, Math.min(1.0, ms.confidence()));
                if (conf < minConfidence) {
                    log.debug("GeminiCurationService: skip low-confidence snippet (score={} < threshold={})",
                            conf, minConfidence);
                    continue;
                }
                try {
                    memoryReinforcementService.reinforceWithSnippet(
                            event.sessionId(),
                            event.userQuery(),
                            text,
                            "ASSISTANT",
                            conf
                    );
                } catch (Throwable t) {
                    log.debug("GeminiCurationService: reinforcement failed (ignored). errorHash={} errorLength={}",
                            SafeRedactor.hashValue(messageOf(t)), messageLength(t));
                }
            }
        } catch (Exception e) {
            log.warn("Applying knowledge delta failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return notApplied("curation_apply_failed", delta);
        }
        traceResult(true, "", delta);
        return new CurationResult(true, "", delta);
    }

    private CurationResult notApplied(String reason, KnowledgeDelta delta) {
        traceResult(false, reason, delta);
        return new CurationResult(false, reason, delta);
    }

    private static KnowledgeDelta emptyDelta() {
        return new KnowledgeDelta(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static boolean isEmpty(KnowledgeDelta delta) {
        if (delta == null) {
            return true;
        }
        return delta.triples().isEmpty()
                && delta.rules().isEmpty()
                && delta.aliases().isEmpty()
                && delta.memories().isEmpty()
                && delta.protectedTerms().isEmpty();
    }

    private void traceResult(boolean applied, String disabledReason, KnowledgeDelta delta) {
        try {
            TraceStore.put("knowledge.curation.enabled", enabled);
            TraceStore.put("knowledge.curation.applied", applied);
            TraceStore.put("knowledge.curation.disabledReason",
                    disabledReason == null || disabledReason.isBlank() ? null : SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
            TraceStore.put("knowledge.curation.triples", delta == null ? 0 : delta.triples().size());
            TraceStore.put("knowledge.curation.rules", delta == null ? 0 : delta.rules().size());
            TraceStore.put("knowledge.curation.memories", delta == null ? 0 : delta.memories().size());
        } catch (Exception ignore) {
            log.debug("GeminiCurationService: trace result skipped errorType={}",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    public record CurationResult(boolean applied, String disabledReason, KnowledgeDelta delta) {
        public CurationResult {
            disabledReason = disabledReason == null ? "" : disabledReason.trim();
            delta = delta == null ? emptyDelta() : delta;
        }
    }
}
