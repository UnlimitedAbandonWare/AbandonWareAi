package com.example.lms.uaw.thumbnail;

import com.example.lms.domain.ChatMessage;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.uaw.presence.UserAbsenceGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UawThumbnailOrchestratorAdmissionTest {

    @TempDir
    Path tempDir;

    @Test
    void overlappingThumbnailTicksAdmitOneGenerationAndRecordTerminalState() throws Exception {
        Path statePath = tempDir.resolve("thumbnail-state.json");
        UawThumbnailProperties props = enabledProperties(statePath);
        UawThumbnailRunStateStore store = new UawThumbnailRunStateStore();
        UawThumbnailBudgetManager budget = new UawThumbnailBudgetManager(props, store);
        UserAbsenceGate absenceGate = mock(UserAbsenceGate.class);
        ChatMessageRepository repository = mock(ChatMessageRepository.class);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        UawThumbnailService thumbnailService = mock(UawThumbnailService.class);
        ChatMessage candidate = mock(ChatMessage.class);
        CountDownLatch firstGenerationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstGeneration = new CountDownLatch(1);
        AtomicInteger generationCalls = new AtomicInteger();

        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        when(candidate.getContent()).thenReturn("bounded thumbnail topic");
        when(repository.findByRoleOrderByIdDesc(eq("user"), any())).thenReturn(List.of(candidate));
        when(knowledgeBase.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(thumbnailService.generateAndPersist(anyString())).thenAnswer(invocation -> {
            if (generationCalls.incrementAndGet() == 1) {
                firstGenerationEntered.countDown();
                assertTrue(releaseFirstGeneration.await(5, TimeUnit.SECONDS),
                        "first thumbnail generation was not released");
            }
            return Optional.empty();
        });

        UawThumbnailOrchestrator orchestrator = new UawThumbnailOrchestrator(
                props, budget, absenceGate, repository, knowledgeBase, thumbnailService, store);
        ExecutorService firstTickExecutor = Executors.newSingleThreadExecutor();
        Future<?> firstTick = firstTickExecutor.submit(orchestrator::tick);
        try {
            assertTrue(firstGenerationEntered.await(5, TimeUnit.SECONDS),
                    "first thumbnail generation did not start");

            orchestrator.tick();

            assertEquals(1, generationCalls.get(),
                    "an overlapping tick must not start another thumbnail generation");
        } finally {
            releaseFirstGeneration.countDown();
            firstTick.get(5, TimeUnit.SECONDS);
            firstTickExecutor.shutdownNow();
        }

        JsonNode persisted = new ObjectMapper().readTree(statePath.toFile());
        assertEquals("succeeded", persisted.path("lastOutcome").asText(),
                "the admitted run must leave a fixed durable terminal outcome");
    }

    @Test
    void failedGenerationRecordsFailureAndReleasesAdmissionLease() throws Exception {
        Path statePath = tempDir.resolve("thumbnail-failure-state.json");
        UawThumbnailProperties props = enabledProperties(statePath);
        UawThumbnailRunStateStore store = new UawThumbnailRunStateStore();
        UawThumbnailBudgetManager budget = new UawThumbnailBudgetManager(props, store);
        UserAbsenceGate absenceGate = mock(UserAbsenceGate.class);
        ChatMessageRepository repository = mock(ChatMessageRepository.class);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        UawThumbnailService thumbnailService = mock(UawThumbnailService.class);
        ChatMessage candidate = mock(ChatMessage.class);

        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        when(candidate.getContent()).thenReturn("failed thumbnail topic");
        when(repository.findByRoleOrderByIdDesc(eq("user"), any())).thenReturn(List.of(candidate));
        when(knowledgeBase.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(thumbnailService.generateAndPersist(anyString()))
                .thenThrow(new IllegalStateException("ownerToken=raw-thumbnail-failure"));

        UawThumbnailOrchestrator orchestrator = new UawThumbnailOrchestrator(
                props, budget, absenceGate, repository, knowledgeBase, thumbnailService, store);

        orchestrator.tick();
        orchestrator.tick();

        verify(thumbnailService, times(2)).generateAndPersist("failed thumbnail topic");
        JsonNode persisted = new ObjectMapper().readTree(statePath.toFile());
        assertEquals("failed", persisted.path("lastOutcome").asText());
    }

    private static UawThumbnailProperties enabledProperties(Path statePath) {
        UawThumbnailProperties props = new UawThumbnailProperties();
        props.setEnabled(true);
        props.setStatePath(statePath.toString());
        props.setIdleCpuThreshold(1.0d);
        props.setMinIntervalSeconds(0L);
        props.setMaxRunsPerDay(8);
        props.setBaseBackoffSeconds(0L);
        props.setMaxBackoffSeconds(0L);
        return props;
    }
}
