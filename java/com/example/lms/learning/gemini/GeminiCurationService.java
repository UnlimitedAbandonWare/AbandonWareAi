package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.MemorySnippet;
import com.example.lms.service.EmbeddingStoreManager;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Gemini 기반 지식 큐레이션 파이프라인의 상위 서비스입니다.
 * - LearningEvent 를 입력으로 받아 KnowledgeDelta 를 생성하고
 * - KnowledgeBase 및 EmbeddingStore 로 적용한 뒤
 * - 고신뢰 MemorySnippet 만 TranslationMemory 강화에 전달합니다.
 */
@Service
@RequiredArgsConstructor
public class GeminiCurationService {

    private static final Logger log = LoggerFactory.getLogger(GeminiCurationService.class);

    private final GeminiClient geminiClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingStoreManager embeddingStoreManager;
    private final MemoryReinforcementService memoryReinforcementService;

    /**
     * 지식 큐레이션에 사용할 기본 모델 ID.
     * - knowledge-curation.model-id
     * - llm.chat-model
     * - openai.chat.model
     * 순으로 해석합니다.
     */
    @Value("${knowledge-curation.model-id:${llm.chat-model:${openai.chat.model:gemini-2.5-pro}}}")
    private String modelId;

    /** Gemini curation HTTP 타임아웃 (초 단위) */
    @Value("${knowledge-curation.timeout-seconds:30}")
    private long timeoutSeconds;

    /** 강화 대상이 될 최소 신뢰도 (0.0~1.0) */
    @Value("${knowledge-curation.min-confidence:0.5}")
    private double minConfidence;

    public KnowledgeDelta ingest(LearningEvent event) {
        if (event == null) {
            return new KnowledgeDelta(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        KnowledgeDelta delta;
        try {
            Duration timeout = Duration.ofSeconds(Math.max(1L, timeoutSeconds));
            String effectiveModel = (modelId == null || modelId.isBlank()) ? "gemini-2.5-pro" : modelId;
            delta = geminiClient.curate(event, effectiveModel, timeout);
        } catch (Exception e) {
            log.warn("Gemini curation failed for sessionId={}: {}", event.sessionId(), e.toString());
            return new KnowledgeDelta(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        if (delta == null) {
            return new KnowledgeDelta(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        try {
            // KnowledgeBase 및 EmbeddingStore에 우선 적용
            knowledgeBaseService.apply(delta);
            embeddingStoreManager.index(delta.memories());

            List<MemorySnippet> memories = delta.memories();
            if (memories == null || memories.isEmpty()) {
                log.debug("GeminiCurationService: no memories in delta → skip reinforcement (sessionId={})",
                        event.sessionId());
                return delta;
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
                    // 강화 실패는 전체 파이프라인을 막지 않도록 soft-fail
                    log.debug("GeminiCurationService: reinforcement failed (ignored): {}", t.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Applying knowledge delta failed: {}", e.toString());
        }
        return delta;
    }
}
