package com.example.lms.service;

import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.reinforcement.SnippetPruner;
import com.example.lms.strategy.StrategyDecisionTracker;
import com.example.lms.strategy.StrategyPerformanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryReinforcementFeedbackContractTest {

    @Test
    void nonexistentDurableRecordCannotMutateEvenWithMatchingHashAndText() {
        TranslationMemoryRepository memoryRepository = mock(TranslationMemoryRepository.class);
        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        SnippetPruner snippetPruner = mock(SnippetPruner.class);
        StrategyPerformanceRepository performanceRepository = mock(StrategyPerformanceRepository.class);
        StrategyDecisionTracker strategyTracker = mock(StrategyDecisionTracker.class);
        HyperparameterService hyperparameters = mock(HyperparameterService.class);
        MemoryReinforcementService service = new MemoryReinforcementService(
                memoryRepository,
                vectorStore,
                snippetPruner,
                performanceRepository,
                strategyTracker,
                hyperparameters);
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        ReflectionTestUtils.setField(service, "chatMessageRepository", chatMessageRepository);
        when(chatMessageRepository.findById(999_999L)).thenReturn(java.util.Optional.empty());
        String content = "arbitrary assistant text";

        assertThrows(MemoryReinforcementService.RatedRecordNotFoundException.class, () ->
                service.applyFeedbackToRatedAssistant(
                        "7",
                        999_999L,
                        com.example.lms.trace.SafeRedactor.hashValue(content),
                        content,
                        true,
                        null));

        verify(chatMessageRepository).findById(999_999L);
        verifyNoInteractions(
                memoryRepository,
                vectorStore,
                snippetPruner,
                performanceRepository,
                strategyTracker,
                hyperparameters);
    }

    @Test
    void exactRatedRecordRejectsMismatchedContentHashBeforeAnyMutation() {
        TranslationMemoryRepository memoryRepository = mock(TranslationMemoryRepository.class);
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        SnippetPruner snippetPruner = mock(SnippetPruner.class);
        StrategyPerformanceRepository performanceRepository = mock(StrategyPerformanceRepository.class);
        StrategyDecisionTracker strategyTracker = mock(StrategyDecisionTracker.class);
        HyperparameterService hyperparameters = mock(HyperparameterService.class);
        MemoryReinforcementService service = new MemoryReinforcementService(
                memoryRepository,
                vectorStore,
                snippetPruner,
                performanceRepository,
                strategyTracker,
                hyperparameters);
        ReflectionTestUtils.setField(service, "memoryEnabled", true);

        assertThrows(IllegalArgumentException.class, () ->
                service.applyFeedbackToRatedAssistant(
                        "7",
                        52L,
                        com.example.lms.trace.SafeRedactor.hashValue("different answer"),
                        "stored assistant answer",
                        true,
                        null));

        verifyNoInteractions(
                memoryRepository,
                vectorStore,
                snippetPruner,
                performanceRepository,
                strategyTracker,
                hyperparameters);
    }

    @Test
    void memoryOffFeedbackPerformsNoPersistenceVectorOrTuningSideEffects() {
        TranslationMemoryRepository memoryRepository = mock(TranslationMemoryRepository.class);
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        SnippetPruner snippetPruner = mock(SnippetPruner.class);
        StrategyPerformanceRepository performanceRepository = mock(StrategyPerformanceRepository.class);
        StrategyDecisionTracker strategyTracker = mock(StrategyDecisionTracker.class);
        HyperparameterService hyperparameters = mock(HyperparameterService.class);
        MemoryReinforcementService service = new MemoryReinforcementService(
                memoryRepository,
                vectorStore,
                snippetPruner,
                performanceRepository,
                strategyTracker,
                hyperparameters);
        ReflectionTestUtils.setField(service, "memoryEnabled", false);

        service.applyFeedbackToRatedAssistant(
                "7",
                52L,
                com.example.lms.trace.SafeRedactor.hashValue("stored assistant answer"),
                "stored assistant answer",
                true,
                "correction");

        verifyNoInteractions(
                memoryRepository,
                vectorStore,
                snippetPruner,
                performanceRepository,
                strategyTracker,
                hyperparameters);
    }
}
