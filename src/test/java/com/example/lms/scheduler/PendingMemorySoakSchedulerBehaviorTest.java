package com.example.lms.scheduler;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.guard.VectorPoisonGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingMemorySoakSchedulerBehaviorTest {

    private static final String SESSION = "pending-session";
    private static final String CONTENT =
            "[W1] supported fact with sufficient detail for pending memory promotion.";

    private TranslationMemoryRepository repository;
    private VectorStoreService vectorStoreService;
    private VectorPoisonGuard vectorPoisonGuard;
    private PendingMemorySoakScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(TranslationMemoryRepository.class);
        vectorStoreService = mock(VectorStoreService.class);
        vectorPoisonGuard = mock(VectorPoisonGuard.class);
        scheduler = new PendingMemorySoakScheduler(repository, vectorStoreService, vectorPoisonGuard);

        ReflectionTestUtils.setField(scheduler, "batchSize", 1);
        ReflectionTestUtils.setField(scheduler, "leaseMinutes", 10L);
        ReflectionTestUtils.setField(scheduler, "maxAgeHours", 72L);
        ReflectionTestUtils.setField(scheduler, "minEvidence", 1);

        when(vectorPoisonGuard.inspectIngest(
                eq(SESSION), eq(CONTENT), anyMap(), eq("pending-soak")))
                .thenReturn(new VectorPoisonGuard.IngestDecision(
                        true, CONTENT, Map.of(), "", 0.0d));
        when(repository.save(any(TranslationMemory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void enqueueThrowReleasesPendingLeaseAndDoesNotFlush() {
        TranslationMemory memory = claimedMemory();
        claimOnce(memory);
        doThrow(new VectorStoreService.VectorQueueCapacityExceededException(1))
                .when(vectorStoreService).enqueue(eq(SESSION), eq(CONTENT), anyMap());

        scheduler.soakPendingMemories();

        assertRetryable(memory, "enqueue_failure");
        verify(repository, times(1)).save(same(memory));
        verify(vectorStoreService, never()).flush();
    }

    @Test
    void backoffFlushOutcomeReleasesPendingLease() {
        TranslationMemory memory = claimedMemory();
        claimOnce(memory);
        when(vectorStoreService.flush())
                .thenReturn(new VectorStoreService.VectorFlushOutcome(false, 0, 1, "backoff"));

        scheduler.soakPendingMemories();

        assertRetryable(memory, "flush_backoff");
        verify(repository, times(1)).save(same(memory));
        verify(vectorStoreService, times(1)).flush();
    }

    @Test
    void storeFailureFlushOutcomeReleasesPendingLease() {
        TranslationMemory memory = claimedMemory();
        claimOnce(memory);
        when(vectorStoreService.flush())
                .thenReturn(new VectorStoreService.VectorFlushOutcome(false, 0, 1, "store_failure"));

        scheduler.soakPendingMemories();

        assertRetryable(memory, "flush_failure");
        verify(repository, times(1)).save(same(memory));
        verify(vectorStoreService, times(1)).flush();
    }

    @Test
    void partialFlushOutcomeReleasesPendingLease() {
        TranslationMemory memory = claimedMemory();
        claimOnce(memory);
        when(vectorStoreService.flush())
                .thenReturn(new VectorStoreService.VectorFlushOutcome(false, 1, 1, "store_failure"));

        scheduler.soakPendingMemories();

        assertRetryable(memory, "flush_partial");
        verify(repository, times(1)).save(same(memory));
        verify(vectorStoreService, times(1)).flush();
    }

    @Test
    void thrownFlushReleasesPendingLease() {
        TranslationMemory memory = claimedMemory();
        claimOnce(memory);
        when(vectorStoreService.flush())
                .thenThrow(new IllegalStateException("private flush failure"));

        scheduler.soakPendingMemories();

        assertRetryable(memory, "flush_failure");
        verify(repository, times(1)).save(same(memory));
        verify(vectorStoreService, times(1)).flush();
    }

    @Test
    void nullFlushOutcomeReleasesPendingLease() {
        TranslationMemory memory = claimedMemory();
        claimOnce(memory);
        when(vectorStoreService.flush()).thenReturn(null);

        scheduler.soakPendingMemories();

        assertRetryable(memory, "flush_failure");
        verify(repository, times(1)).save(same(memory));
        verify(vectorStoreService, times(1)).flush();
    }

    @Test
    void expiredPendingClaimRetriesTheSameVectorUpsertThenActivatesOnceDurable() {
        TranslationMemory memory = claimedMemory();
        claimTwice(memory);
        when(vectorStoreService.flush()).thenReturn(
                new VectorStoreService.VectorFlushOutcome(false, 0, 1, "backoff"),
                new VectorStoreService.VectorFlushOutcome(true, 1, 0, "complete"));

        scheduler.soakPendingMemories();

        assertRetryable(memory, "flush_backoff");
        memory.setLockedAt(LocalDateTime.now().minusMinutes(11));
        memory.setLockedBy("expired-owner");

        scheduler.soakPendingMemories();

        assertEquals(TranslationMemory.MemoryStatus.ACTIVE, memory.getStatus());
        assertNull(memory.getLockedAt());
        assertNull(memory.getLockedBy());
        verify(repository, times(2)).save(same(memory));
        verify(vectorStoreService, times(2)).flush();

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorStoreService, times(2))
                .enqueue(sessionCaptor.capture(), contentCaptor.capture(), anyMap());
        assertEquals(List.of(SESSION, SESSION), sessionCaptor.getAllValues());
        assertEquals(List.of(CONTENT, CONTENT), contentCaptor.getAllValues());
    }

    @Test
    void durableEmptyOutcomeActivatesAfterSuccessfulEnqueue() {
        TranslationMemory memory = claimedMemory();
        claimOnce(memory);
        when(vectorStoreService.flush())
                .thenReturn(new VectorStoreService.VectorFlushOutcome(true, 0, 0, "empty"));

        scheduler.soakPendingMemories();

        assertEquals(TranslationMemory.MemoryStatus.ACTIVE, memory.getStatus());
        assertNull(memory.getLockedAt());
        assertNull(memory.getLockedBy());
        verify(repository, times(1)).save(same(memory));
        verify(vectorStoreService, times(1)).flush();
    }

    private TranslationMemory claimedMemory() {
        TranslationMemory memory = new TranslationMemory("source-hash");
        memory.setId(41L);
        memory.setStatus(TranslationMemory.MemoryStatus.PENDING);
        memory.setSessionId(SESSION);
        memory.setContent(CONTENT);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setLockedAt(LocalDateTime.now());
        memory.setLockedBy("claimed-owner");
        return memory;
    }

    private void claimOnce(TranslationMemory memory) {
        stubClaim(memory, 1);
    }

    private void claimTwice(TranslationMemory memory) {
        stubClaim(memory, 2);
    }

    private void stubClaim(TranslationMemory memory, int attempts) {
        Integer[] claims = new Integer[attempts];
        @SuppressWarnings("unchecked")
        List<TranslationMemory>[] batches = new List[attempts];
        for (int i = 0; i < attempts; i++) {
            claims[i] = 1;
            batches[i] = List.of(memory);
        }

        when(repository.claimPendingLease(
                eq(TranslationMemory.MemoryStatus.PENDING.ordinal()),
                anyString(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(1)))
                .thenReturn(claims[0], java.util.Arrays.copyOfRange(claims, 1, claims.length));
        when(repository.findByLockedByAndLockedAtOrderByCreatedAtAsc(
                anyString(), any(LocalDateTime.class)))
                .thenReturn(batches[0], java.util.Arrays.copyOfRange(batches, 1, batches.length));
    }

    private static void assertRetryable(TranslationMemory memory, String reasonCode) {
        assertEquals(TranslationMemory.MemoryStatus.PENDING, memory.getStatus());
        assertNull(memory.getLockedAt());
        assertNull(memory.getLockedBy());
        assertEquals(reasonCode, TraceStore.get("pendingSoak.retry.reason"));
    }
}
