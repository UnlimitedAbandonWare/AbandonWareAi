package com.example.lms.service.understanding;

import com.example.lms.service.*;
import com.example.lms.guard.*;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import com.github.benmanes.caffeine.cache.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class UnderstandingLocalReinforcementTest {
    static final String SNIPPET = "A synthetic factual explanation has sufficient detail for the ordinary memory storage quality checks.";
    TranslationMemoryRepository repository;
    VectorStoreService vectors;
    GuardProfileProps currentRequestProfile;
    MemoryReinforcementService memory;
    TransactionTemplate tx;
    LoadingCache<String, Boolean> cache;
    DeferredUnderstandingTask task;
    @BeforeEach void setup() {
        repository = mock(TranslationMemoryRepository.class);
        vectors = mock(VectorStoreService.class);
        currentRequestProfile = mock(GuardProfileProps.class);
        when(currentRequestProfile.currentProfile()).thenReturn(GuardProfile.PROFILE_FREE);
        when(repository.findBySourceHash(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(TranslationMemory.class))).thenAnswer(c -> c.getArgument(0));
        memory = new MemoryReinforcementService(repository, vectors,
                mock(com.example.lms.service.reinforcement.SnippetPruner.class),
                mock(com.example.lms.strategy.StrategyPerformanceRepository.class),
                mock(com.example.lms.strategy.StrategyDecisionTracker.class),
                mock(com.example.lms.service.config.HyperparameterService.class));
        ReflectionTestUtils.setField(memory, "memoryEnabled", true);
        ReflectionTestUtils.setField(memory, "guardProfileProps", currentRequestProfile);
        cache = Caffeine.newBuilder().build(k -> Boolean.TRUE);
        ReflectionTestUtils.setField(memory, "recentSnippetCache", cache);
        tx = new TransactionTemplate(new DataSourceTransactionManager(
                new DriverManagerDataSource("jdbc:h2:mem:local_memory_" + UUID.randomUUID(), "sa", "")));
        task = new DeferredUnderstandingTask(1, "run", "a".repeat(64), 1, "GENERAL", 1,
                2, 100, 3, 200, "UNDERSTANDING", "b".repeat(64), "c".repeat(64),
                GuardProfile.NORMAL, MemoryMode.FULL, true, true, true, "synthetic", 500);
    }
    @Test void approvedSnapshotReusesLocalStorageWithoutVectorsOrRequestPolicy() {
        tx.executeWithoutResult(t -> memory.reinforceUnderstandingLocal(task, "synthetic question", SNIPPET, .95));
        var record = org.mockito.ArgumentCaptor.forClass(TranslationMemory.class);
        verify(repository).save(record.capture());
        assertEquals("chat-1", record.getValue().getSessionId());
        assertEquals(SNIPPET, record.getValue().getContent());
        assertEquals(1, record.getValue().getHitCount());
        verifyNoInteractions(vectors, currentRequestProfile);
    }
    @Test void SQLFailureIsNotSwallowed() {
        var failure = new org.springframework.dao.DataIntegrityViolationException("synthetic");
        doThrow(failure).when(repository).save(any(TranslationMemory.class));
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> tx.executeWithoutResult(t -> memory.reinforceUnderstandingLocal(task, "q", SNIPPET, .95))));
        verifyNoInteractions(vectors);
    }
    @Test void cacheIsUpdatedOnlyAfterCommitAndRollbackLeavesRetryPossible() {
        String hash = org.apache.commons.codec.digest.DigestUtils.sha1Hex(SNIPPET);
        tx.executeWithoutResult(t -> {
            memory.reinforceUnderstandingLocal(task, "q", SNIPPET, .8);
            assertNull(cache.getIfPresent(hash));
            t.setRollbackOnly();
        });
        assertNull(cache.getIfPresent(hash));
        tx.executeWithoutResult(t -> memory.reinforceUnderstandingLocal(task, "q", SNIPPET, .8));
        assertEquals(Boolean.TRUE, cache.getIfPresent(hash));
        verify(repository, times(2)).save(any(TranslationMemory.class));
        verifyNoInteractions(vectors);
    }
    @Test void existingOtherSessionRowIsNotReinforced() {
        TranslationMemory foreign = new TranslationMemory("a".repeat(40));
        foreign.setId(99L); foreign.setSessionId("chat-2");
        when(repository.findBySourceHash(anyString())).thenReturn(Optional.of(foreign));
        tx.executeWithoutResult(t -> memory.reinforceUnderstandingLocal(task, "q", SNIPPET, .95));
        verify(repository, never()).save(any());
        verify(repository, never()).updateEnergyByHashAndSession(anyString(), anyString(), anyDouble(), anyDouble());
        assertEquals(0, foreign.getHitCount());
        verifyNoInteractions(vectors);
    }
    @Test void originApprovalCapturesSensitiveAndForceOffVetoes() {
        var guard = new com.example.lms.service.guard.GuardContext();
        com.example.lms.service.guard.GuardContextHolder.set(guard);
        try {
            assertTrue(memory.understandingMemoryApproved());
            guard.setSensitiveTopic(true);
            org.junit.jupiter.api.Assertions.assertFalse(memory.understandingMemoryApproved());
            guard.setSensitiveTopic(false);
            guard.putPlanOverride("memory.forceOff", true);
            org.junit.jupiter.api.Assertions.assertFalse(memory.understandingMemoryApproved());
        } finally { com.example.lms.service.guard.GuardContextHolder.clear(); }
        verifyNoInteractions(repository, vectors);
    }
    @Test void localEntryRejectsAutocommit() {
        assertThrows(IllegalStateException.class, () -> memory.reinforceUnderstandingLocal(task, "q", SNIPPET, .9));
        verifyNoInteractions(repository, vectors);
    }
}
