package com.example.lms.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorStoreServiceFlushOutcomeTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void outcomeNormalizesCountsAndBlankReason() {
        VectorStoreService.VectorFlushOutcome outcome =
                new VectorStoreService.VectorFlushOutcome(false, -4, -3, " ");

        assertFalse(outcome.durable());
        assertEquals(0, outcome.succeededCount());
        assertEquals(0, outcome.pendingCount());
        assertEquals("unknown", outcome.reasonCode());
    }

    @Test
    void outcomeRejectsAnUnrecognizedSensitiveReasonCode() {
        String rawReason = "sid=private-session deterministic store failure";

        VectorStoreService.VectorFlushOutcome outcome =
                new VectorStoreService.VectorFlushOutcome(false, 0, 0, rawReason);

        assertEquals("unknown", outcome.reasonCode());
        assertFalse(outcome.toString().contains(rawReason), outcome.toString());
        assertFalse(outcome.toString().contains("private-session"), outcome.toString());
    }

    @Test
    void flushReturnsCompleteAfterACompleteSnapshotStore() throws Exception {
        RecordingStore store = new RecordingStore(-1);
        VectorStoreService service = newService(store);

        service.enqueue("complete-id", "complete-session", "complete payload", Map.of());

        VectorStoreService.VectorFlushOutcome outcome = service.flush();

        assertTrue(outcome.durable());
        assertEquals(1, outcome.succeededCount());
        assertEquals(0, outcome.pendingCount());
        assertEquals("complete", outcome.reasonCode());
        assertEquals(1, store.addAllCalls);
        assertEquals(List.of(List.of("complete-id")), store.acceptedIds);
    }

    @Test
    void flushReturnsEmptyWhenTheSnapshotIsEmpty() throws Exception {
        RecordingStore store = new RecordingStore(-1);
        VectorStoreService service = newService(store);

        VectorStoreService.VectorFlushOutcome outcome = service.flush();

        assertTrue(outcome.durable());
        assertEquals(0, outcome.succeededCount());
        assertEquals(0, outcome.pendingCount());
        assertEquals("empty", outcome.reasonCode());
        assertEquals(0, store.addAllCalls);
    }

    @Test
    void flushReturnsStoreFailureAndRestoresTheFailedSnapshotWithoutLeakingDetails() throws Exception {
        RecordingStore store = new RecordingStore(1);
        VectorStoreService service = newService(store);
        String rawId = "failed-vector-id";
        String rawSession = "failed-private-session";
        String rawText = "failed private payload";

        service.enqueue(rawId, rawSession, rawText, Map.of());

        VectorStoreService.VectorFlushOutcome outcome = service.flush();

        assertFalse(outcome.durable());
        assertEquals(0, outcome.succeededCount());
        assertEquals(1, outcome.pendingCount());
        assertEquals("store_failure", outcome.reasonCode());
        assertEquals(1, service.pendingSize());
        assertEquals(1, store.addAllCalls);

        String outcomeText = outcome.toString();
        assertFalse(outcomeText.contains(rawId), outcomeText);
        assertFalse(outcomeText.contains(rawSession), outcomeText);
        assertFalse(outcomeText.contains(rawText), outcomeText);
        assertFalse(outcomeText.contains(RecordingStore.FAILURE_MESSAGE), outcomeText);
    }

    @Test
    void flushReturnsBackoffWithoutSwappingOrStoringThePendingQueue() throws Exception {
        RecordingStore store = new RecordingStore(-1);
        VectorStoreService service = newService(store);
        service.enqueue("backoff-id", "backoff-session", "backoff payload", Map.of());
        setLong(service, "backoffUntilEpochMs", Long.MAX_VALUE);

        VectorStoreService.VectorFlushOutcome outcome = service.flush();

        assertFalse(outcome.durable());
        assertEquals(0, outcome.succeededCount());
        assertEquals(1, outcome.pendingCount());
        assertEquals("backoff", outcome.reasonCode());
        assertEquals(1, service.pendingSize());
        assertEquals(0, store.addAllCalls);
    }

    @Test
    void flushReportsPartialSuccessWhenTheSecondTraceGroupFails() throws Exception {
        RecordingStore store = new RecordingStore(2);
        VectorStoreService service = newService(store);

        MDC.put("traceId", "flush-outcome-group-one");
        MDC.put("x-request-id", "flush-outcome-request-one");
        service.enqueue("partial-one", "partial-session", "first payload", Map.of());

        MDC.put("traceId", "flush-outcome-group-two");
        MDC.put("x-request-id", "flush-outcome-request-two");
        service.enqueue("partial-two", "partial-session", "second payload", Map.of());

        VectorStoreService.VectorFlushOutcome outcome = service.flush();

        assertFalse(outcome.durable());
        assertEquals(1, outcome.succeededCount());
        assertEquals(1, outcome.pendingCount());
        assertEquals("store_failure", outcome.reasonCode());
        assertEquals(1, service.pendingSize());
        assertEquals(2, store.addAllCalls);
        assertEquals(1, store.acceptedIds.size());
        assertEquals(1, store.acceptedIds.get(0).size());
    }

    @Test
    void capacityRejectsOnlyNewUniqueLiveEntriesWithoutLeakingDetails() throws Exception {
        RecordingStore store = new RecordingStore(-1);
        VectorStoreService service = newService(store);
        setInt(service, "queueMaxPending", 3);
        setLong(service, "backoffUntilEpochMs", Long.MAX_VALUE);

        service.enqueue("retained-one", "capacity-session", "retained payload one", Map.of());
        service.enqueue("retained-two", "capacity-session", "retained payload two", Map.of());
        service.enqueue("retained-three", "capacity-session", "retained payload three", Map.of());

        long rejectedBeforeDuplicate = service.bufferStats().rejectedCount();
        service.enqueue("retained-one", "capacity-session", "new duplicate payload", Map.of());
        assertEquals(3, service.pendingSize());
        assertEquals(rejectedBeforeDuplicate, service.bufferStats().rejectedCount());

        String rejectedId = "rejected-private-id";
        String rejectedSession = "rejected-private-session";
        String rejectedText = "rejected private payload";
        VectorStoreService.VectorQueueCapacityExceededException rejected = assertThrows(
                VectorStoreService.VectorQueueCapacityExceededException.class,
                () -> service.enqueue(rejectedId, rejectedSession, rejectedText, Map.of()));

        VectorStoreService.VectorBufferStats stats = service.bufferStats();
        assertEquals(3, service.pendingSize());
        assertEquals(3, stats.capacity());
        assertEquals(0, stats.inFlight());
        assertEquals(rejectedBeforeDuplicate + 1, stats.rejectedCount());
        assertEquals(3, rejected.capacity());
        assertFalse(rejected.toString().contains(rejectedId), rejected.toString());
        assertFalse(rejected.toString().contains(rejectedSession), rejected.toString());
        assertFalse(rejected.toString().contains(rejectedText), rejected.toString());
    }

    @Test
    @Timeout(10)
    void inFlightEntriesConsumeCapacityUntilStoreCompletes() throws Exception {
        RecordingStore store = new RecordingStore(-1, 1);
        VectorStoreService service = newService(store);
        setInt(service, "queueMaxPending", 2);
        setLong(service, "backoffUntilEpochMs", Long.MAX_VALUE);
        service.enqueue("inflight-one", "inflight-session", "first in-flight payload", Map.of());
        service.enqueue("inflight-two", "inflight-session", "second in-flight payload", Map.of());
        setLong(service, "backoffUntilEpochMs", 0L);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<VectorStoreService.VectorFlushOutcome> flushFuture = executor.submit(service::flush);
            assertTrue(store.awaitBlocked(2, TimeUnit.SECONDS), "flush did not reach the deterministic store latch");

            VectorStoreService.VectorBufferStats blockedStats = service.bufferStats();
            assertEquals(2, blockedStats.queued());
            assertEquals(2, blockedStats.inFlight());
            assertThrows(VectorStoreService.VectorQueueCapacityExceededException.class,
                    () -> service.enqueue("inflight-three", "inflight-session", "overflow payload", Map.of()));

            store.releaseBlocked();
            VectorStoreService.VectorFlushOutcome outcome = flushFuture.get(2, TimeUnit.SECONDS);
            assertTrue(outcome.durable());
            assertEquals(0, service.pendingSize());
            assertEquals(0, service.bufferStats().inFlight());

            service.enqueue("inflight-three", "inflight-session", "accepted after release", Map.of());
            assertEquals(1, service.pendingSize());
        } finally {
            store.releaseBlocked();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedSnapshotRemainsBoundedAndSuccessfulRetryReleasesCapacity() throws Exception {
        RecordingStore store = new RecordingStore(1);
        VectorStoreService service = newService(store);
        setInt(service, "queueMaxPending", 3);
        setLong(service, "backoffUntilEpochMs", Long.MAX_VALUE);
        service.enqueue("bounded-one", "bounded-session", "bounded payload one", Map.of());
        service.enqueue("bounded-two", "bounded-session", "bounded payload two", Map.of());
        service.enqueue("bounded-three", "bounded-session", "bounded payload three", Map.of());

        setLong(service, "backoffUntilEpochMs", 0L);
        VectorStoreService.VectorFlushOutcome failed = service.flush();
        assertFalse(failed.durable());
        assertEquals("store_failure", failed.reasonCode());
        assertEquals(3, service.pendingSize());
        assertEquals(0, service.bufferStats().inFlight());
        assertThrows(VectorStoreService.VectorQueueCapacityExceededException.class,
                () -> service.enqueue("bounded-four", "bounded-session", "bounded overflow", Map.of()));

        setLong(service, "backoffUntilEpochMs", 0L);
        VectorStoreService.VectorFlushOutcome recovered = service.flush();
        assertTrue(recovered.durable());
        assertEquals(0, service.pendingSize());
        service.enqueue("bounded-four", "bounded-session", "accepted after retry", Map.of());
        assertEquals(1, service.pendingSize());
    }

    @Test
    @Timeout(10)
    void failedOldSnapshotCannotOverwriteNewerSameIdOrLeakItsSlot() throws Exception {
        RecordingStore store = new RecordingStore(1, 1);
        VectorStoreService service = newService(store);
        setInt(service, "queueMaxPending", 3);
        String oldA = "old payload A";
        String newA = "new payload A";
        service.enqueue("same-A", "same-session", oldA, Map.of());
        service.enqueue("same-B", "same-session", "payload B", Map.of());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<VectorStoreService.VectorFlushOutcome> flushFuture = executor.submit(service::flush);
            assertTrue(store.awaitBlocked(2, TimeUnit.SECONDS));
            service.enqueue("same-A", "same-session", newA, Map.of());
            store.releaseBlocked();

            VectorStoreService.VectorFlushOutcome failed = flushFuture.get(2, TimeUnit.SECONDS);
            assertFalse(failed.durable());
            assertEquals(2, service.pendingSize());
            assertEquals(0, service.bufferStats().inFlight());

            service.enqueue("same-C", "same-session", "payload C", Map.of());
            assertThrows(VectorStoreService.VectorQueueCapacityExceededException.class,
                    () -> service.enqueue("same-D", "same-session", "payload D", Map.of()));

            setLong(service, "backoffUntilEpochMs", 0L);
            assertTrue(service.flush().durable());
            List<String> storedAfterFailure = store.acceptedTextsFrom(0);
            assertTrue(storedAfterFailure.contains(newA), storedAfterFailure.toString());
            assertFalse(storedAfterFailure.contains(oldA), storedAfterFailure.toString());
            assertEquals(0, service.pendingSize());
        } finally {
            store.releaseBlocked();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(10)
    void partialSuccessAndSameIdSupersessionConsumeEverySlotExactlyOnce() throws Exception {
        RecordingStore store = new RecordingStore(2, 1);
        VectorStoreService service = newService(store);
        setInt(service, "queueMaxPending", 4);
        String oldA = "partial old A";
        String oldB = "partial old B";
        String newA = "partial new A";
        String newB = "partial new B";
        enqueueWithTrace(service, "partial-A", oldA, "partial-old-trace-A");
        enqueueWithTrace(service, "partial-B", oldB, "partial-old-trace-B");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<VectorStoreService.VectorFlushOutcome> flushFuture = executor.submit(service::flush);
            assertTrue(store.awaitBlocked(2, TimeUnit.SECONDS));
            enqueueWithTrace(service, "partial-A", newA, "partial-new-trace-A");
            enqueueWithTrace(service, "partial-B", newB, "partial-new-trace-B");
            store.releaseBlocked();

            VectorStoreService.VectorFlushOutcome failed = flushFuture.get(2, TimeUnit.SECONDS);
            assertFalse(failed.durable());
            assertEquals(1, failed.succeededCount());
            assertEquals(2, service.pendingSize());
            assertEquals(0, service.bufferStats().inFlight());

            service.enqueue("partial-C", "partial-session", "partial payload C", Map.of());
            service.enqueue("partial-D", "partial-session", "partial payload D", Map.of());
            assertThrows(VectorStoreService.VectorQueueCapacityExceededException.class,
                    () -> service.enqueue("partial-E", "partial-session", "partial payload E", Map.of()));

            setLong(service, "backoffUntilEpochMs", 0L);
            assertTrue(service.flush().durable());
            List<String> recoveredTexts = store.acceptedTextsFrom(1);
            assertTrue(recoveredTexts.containsAll(List.of(newA, newB, "partial payload C", "partial payload D")),
                    recoveredTexts.toString());
            assertFalse(recoveredTexts.contains(oldA), recoveredTexts.toString());
            assertFalse(recoveredTexts.contains(oldB), recoveredTexts.toString());
            assertEquals(0, service.pendingSize());
        } finally {
            store.releaseBlocked();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(10)
    void thresholdFlushWaitsWithoutHoldingQueueMutexAndDrainsWithoutScheduler() throws Exception {
        RecordingStore store = new RecordingStore(-1, 1);
        VectorStoreService service = newService(store);
        setInt(service, "queueMaxPending", 4);
        setInt(service, "batchSize", 2);
        service.enqueue("lock-old", "lock-session", "old in-flight payload", Map.of());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch thresholdStarted = new CountDownLatch(1);
        try {
            Future<VectorStoreService.VectorFlushOutcome> firstFlush = executor.submit(service::flush);
            assertTrue(store.awaitBlocked(2, TimeUnit.SECONDS));
            enqueueWithTrace(service, "lock-live-one", "first live payload", "lock-live-trace");
            Future<?> thresholdEnqueue = executor.submit(() -> {
                thresholdStarted.countDown();
                enqueueWithTrace(service, "lock-live-two", "second live payload", "lock-live-trace");
            });
            assertTrue(thresholdStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> thresholdEnqueue.get(150, TimeUnit.MILLISECONDS),
                    "threshold enqueue should wait for the active synchronized flush");

            store.releaseBlocked();
            assertTrue(firstFlush.get(2, TimeUnit.SECONDS).durable());
            thresholdEnqueue.get(2, TimeUnit.SECONDS);
            assertEquals(0, service.pendingSize());
            assertEquals(0, service.bufferStats().inFlight());
            assertEquals(2, store.addAllCalls);
        } finally {
            store.releaseBlocked();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void nonPositiveCapacityFallsBackToThePositiveDefault() throws Exception {
        VectorStoreService service = newService(new RecordingStore(-1));
        setInt(service, "queueMaxPending", 0);
        assertEquals(2048, service.bufferStats().capacity());
        setInt(service, "queueMaxPending", -7);
        assertEquals(2048, service.bufferStats().capacity());
    }

    private static VectorStoreService newService(RecordingStore store) throws Exception {
        VectorStoreService service = new VectorStoreService(new FixedEmbeddingModel(), store);
        setInt(service, "batchSize", 1000);
        setInt(service, "queueMaxPending", 2048);
        setBoolean(service, "shadowWriteEnabled", false);
        setLong(service, "initialBackoffMs", 1_000L);
        setLong(service, "maxBackoffMs", 60_000L);
        return service;
    }

    private static void enqueueWithTrace(
            VectorStoreService service, String id, String text, String traceId) {
        String oldTrace = MDC.get("traceId");
        String oldRequest = MDC.get("x-request-id");
        MDC.put("traceId", traceId);
        MDC.put("x-request-id", traceId + "-request");
        try {
            service.enqueue(id, "partial-session", text, Map.of());
        } finally {
            restoreMdc("traceId", oldTrace);
            restoreMdc("x-request-id", oldRequest);
        }
    }

    private static void restoreMdc(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static void setInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setLong(Object target, String fieldName, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static final class FixedEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream()
                    .map(ignored -> Embedding.from(new float[]{1.0f}))
                    .toList());
        }
    }

    private static final class RecordingStore implements EmbeddingStore<TextSegment> {
        private static final String FAILURE_MESSAGE = "deterministic store failure";

        private final int failOnAddAllCall;
        private final int blockOnAddAllCall;
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<List<String>> acceptedIds = new ArrayList<>();
        private final List<List<String>> acceptedTexts = new ArrayList<>();
        private int addAllCalls;

        private RecordingStore(int failOnAddAllCall) {
            this(failOnAddAllCall, -1);
        }

        private RecordingStore(int failOnAddAllCall, int blockOnAddAllCall) {
            this.failOnAddAllCall = failOnAddAllCall;
            this.blockOnAddAllCall = blockOnAddAllCall;
        }

        private boolean awaitBlocked(long timeout, TimeUnit unit) throws InterruptedException {
            return blocked.await(timeout, unit);
        }

        private void releaseBlocked() {
            release.countDown();
        }

        private List<String> acceptedTextsFrom(int successfulBatchIndex) {
            return acceptedTexts.subList(successfulBatchIndex, acceptedTexts.size()).stream()
                    .flatMap(List::stream)
                    .toList();
        }

        @Override
        public String add(Embedding embedding) {
            return "unused";
        }

        @Override
        public void add(String id, Embedding embedding) {
        }

        @Override
        public String add(Embedding embedding, TextSegment embedded) {
            return "unused";
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            return List.of();
        }

        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
            addAllCalls++;
            if (addAllCalls == blockOnAddAllCall) {
                blocked.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("deterministic store latch timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("deterministic store interrupted", interrupted);
                }
            }
            if (addAllCalls == failOnAddAllCall) {
                throw new IllegalStateException(FAILURE_MESSAGE);
            }
            acceptedIds.add(List.copyOf(ids));
            acceptedTexts.add(embedded.stream().map(TextSegment::text).toList());
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            return new EmbeddingSearchResult<>(List.of());
        }
    }
}
