package com.example.lms.service;

import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.reinforcement.SnippetPruner;
import com.example.lms.strategy.StrategyDecisionTracker;
import com.example.lms.strategy.StrategyPerformanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class LearningSourceTagNormalizationTest {

    @Test
    void memoryReinforcementPreservesActiveLearningSourceTagsWithoutWebOrigin() {
        MemoryReinforcementService service = new MemoryReinforcementService(
                mock(TranslationMemoryRepository.class),
                mock(VectorStoreService.class),
                mock(SnippetPruner.class),
                mock(StrategyPerformanceRepository.class),
                mock(StrategyDecisionTracker.class),
                mock(HyperparameterService.class));

        assertEquals("SUBMISSION", ReflectionTestUtils.invokeMethod(service, "normalizeSourceTag", "submission"));
        assertEquals("FEEDBACK", ReflectionTestUtils.invokeMethod(service, "normalizeSourceTag", "FEEDBACK"));
        assertEquals("USER", ReflectionTestUtils.invokeMethod(service, "deriveOrigin", "SUBMISSION"));
        assertEquals("USER", ReflectionTestUtils.invokeMethod(service, "deriveOrigin", "FEEDBACK"));
        assertEquals("SYSTEM", ReflectionTestUtils.invokeMethod(service, "deriveOrigin", "GRADE"));
        assertEquals("WEB", ReflectionTestUtils.invokeMethod(service, "normalizeSourceTag", "lms_corpus"));

        Map<String, Object> meta = ReflectionTestUtils.invokeMethod(
                service,
                "buildVectorMeta",
                "rag_ops",
                false,
                0);

        assertEquals("RAG_OPS", meta.get(VectorMetaKeys.META_SOURCE_TAG));
        assertEquals("SYSTEM", meta.get(VectorMetaKeys.META_ORIGIN));
    }

    @Test
    void embeddingStoreBootstrapPreservesActiveLearningSourceTagsWithoutWebOrigin() {
        assertEquals("SUBMISSION", ReflectionTestUtils.invokeMethod(
                EmbeddingStoreManager.class,
                "normalizeSourceTag",
                "submission"));
        assertEquals("FEEDBACK", ReflectionTestUtils.invokeMethod(
                EmbeddingStoreManager.class,
                "normalizeSourceTag",
                "FEEDBACK"));
        assertEquals("WEB", ReflectionTestUtils.invokeMethod(
                EmbeddingStoreManager.class,
                "normalizeSourceTag",
                "lms_corpus"));
        assertEquals("USER", ReflectionTestUtils.invokeMethod(
                EmbeddingStoreManager.class,
                "deriveOrigin",
                "SUBMISSION"));
        assertEquals("USER", ReflectionTestUtils.invokeMethod(
                EmbeddingStoreManager.class,
                "deriveOrigin",
                "FEEDBACK"));
        assertEquals("SYSTEM", ReflectionTestUtils.invokeMethod(
                EmbeddingStoreManager.class,
                "deriveOrigin",
                "AUTOLEARN"));
    }
}
