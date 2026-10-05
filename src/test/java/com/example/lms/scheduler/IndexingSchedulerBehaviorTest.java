package com.example.lms.scheduler;

import com.example.lms.search.TraceStore;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.vector.VectorShadowMergeDlqService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexingSchedulerBehaviorTest {

    @AfterEach
    void clearDiagnostics() {
        MDC.clear();
        TraceStore.clear();
    }

    @Test
    void capacityExceptionDoesNotReinforceFlushOrAdvanceCursor() {
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        VectorStoreService vectors = mock(VectorStoreService.class);
        doThrow(new VectorStoreService.VectorQueueCapacityExceededException(1))
                .when(vectors).enqueue(anyString(), anyString(), anyMap());
        IndexingScheduler.DocumentFetcher fetcher = mock(IndexingScheduler.DocumentFetcher.class);
        MemoryReinforcementService memory = mock(MemoryReinforcementService.class);
        List<Document> documents = List.of(Document.from(
                "Capacity-rejected indexing content with enough detail for one stable segment."));
        when(fetcher.fetchNewDocumentsSince(any(LocalDateTime.class))).thenReturn(documents);
        IndexingScheduler scheduler = new IndexingScheduler(
                new FixedEmbeddingModel(), store, fetcher, memory, vectors);

        scheduler.scheduleIndexing();
        scheduler.scheduleIndexing();

        ArgumentCaptor<LocalDateTime> cursors = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fetcher, times(2)).fetchNewDocumentsSince(cursors.capture());
        assertEquals(cursors.getAllValues().get(0), cursors.getAllValues().get(1));
        verify(memory, never()).reinforceWithSnippet(
                anyString(), any(), anyString(), anyString(), anyDouble());
        verify(vectors, never()).flush();
        assertEquals("exception", TraceStore.get("indexing.flush.failed.reason"));
    }

    @Test
    void failureThenBackoffRetryDoesNotReinforceOrAdvanceCursor() {
        RecordingStore store = new RecordingStore(1);
        VectorStoreService vectors = newService(store);
        IndexingScheduler.DocumentFetcher fetcher = mock(IndexingScheduler.DocumentFetcher.class);
        MemoryReinforcementService memory = mock(MemoryReinforcementService.class);
        List<Document> documents = List.of(Document.from(
                "Retryable indexing content with enough detail for one stable segment."));
        when(fetcher.fetchNewDocumentsSince(any(LocalDateTime.class))).thenReturn(documents);
        IndexingScheduler scheduler = new IndexingScheduler(
                new FixedEmbeddingModel(), store, fetcher, memory, vectors);

        scheduler.scheduleIndexing();
        assertEquals("store_failure", TraceStore.get("indexing.flush.failed.reason"));

        scheduler.scheduleIndexing();
        assertEquals("backoff", TraceStore.get("indexing.flush.failed.reason"));

        ArgumentCaptor<LocalDateTime> cursors = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fetcher, times(2)).fetchNewDocumentsSince(cursors.capture());
        assertEquals(cursors.getAllValues().get(0), cursors.getAllValues().get(1));
        verify(memory, never()).reinforceWithSnippet(
                anyString(), any(), anyString(), anyString(), anyDouble());
        assertEquals(1, store.addAllCalls,
                "backoff must suppress another physical store attempt");
    }

    @Test
    void partialTwoTraceGroupFlushDoesNotReinforceOrAdvanceCursor() {
        RecordingStore store = new RecordingStore(2);
        TracePerPayloadVectorStoreService vectors =
                new TracePerPayloadVectorStoreService(new FixedEmbeddingModel(), store);
        configure(vectors);
        IndexingScheduler.DocumentFetcher fetcher = mock(IndexingScheduler.DocumentFetcher.class);
        MemoryReinforcementService memory = mock(MemoryReinforcementService.class);
        List<Document> documents = List.of(
                Document.from("First distinct indexing document with stable factual content."),
                Document.from("Second distinct indexing document with other factual content."));
        when(fetcher.fetchNewDocumentsSince(any(LocalDateTime.class)))
                .thenReturn(documents, documents, documents, List.of());
        IndexingScheduler scheduler = new IndexingScheduler(
                new FixedEmbeddingModel(), store, fetcher, memory, vectors);

        scheduler.scheduleIndexing();

        verify(memory, never()).reinforceWithSnippet(
                anyString(), any(), anyString(), anyString(), anyDouble());
        assertEquals("partial", TraceStore.get("indexing.flush.failed.reason"));
        assertEquals(1, vectors.pendingSize());
        assertEquals(2, store.attemptedIds.size());
        Set<String> firstAttemptIds = flattenIds(store.attemptedIds, 0, 2);
        assertEquals(2, firstAttemptIds.size());

        scheduler.scheduleIndexing();

        verify(memory, never()).reinforceWithSnippet(
                anyString(), any(), anyString(), anyString(), anyDouble());
        assertEquals("backoff", TraceStore.get("indexing.flush.failed.reason"));
        assertEquals(2, store.addAllCalls,
                "the second schedule must remain inside real vector backoff");
        assertEquals(1, store.acceptedIds.size());

        ReflectionTestUtils.setField(vectors, "backoffUntilEpochMs", 0L);
        scheduler.scheduleIndexing();

        assertEquals(0, vectors.pendingSize());
        assertEquals(4, store.addAllCalls);
        assertEquals(4, store.attemptedIds.size());
        Set<String> retryAttemptIds = flattenIds(store.attemptedIds, 2, 4);
        assertEquals(firstAttemptIds, retryAttemptIds,
                "the first real post-backoff retry must resubmit the same deterministic vector IDs");
        assertEquals(firstAttemptIds, store.persistedIds,
                "the fake store models ID-keyed upsert rather than duplicate durable rows");
        verify(memory, times(2)).reinforceWithSnippet(
                eq("0"), isNull(), anyString(), eq("WEB"), eq(1.0d));

        scheduler.scheduleIndexing();

        ArgumentCaptor<LocalDateTime> cursors = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fetcher, times(4)).fetchNewDocumentsSince(cursors.capture());
        assertEquals(cursors.getAllValues().get(0), cursors.getAllValues().get(1));
        assertEquals(cursors.getAllValues().get(0), cursors.getAllValues().get(2));
        assertTrue(cursors.getAllValues().get(3).isAfter(cursors.getAllValues().get(2)));
    }

    @Test
    void durableFlushReinforcesEachSegmentOnceAndAdvancesCursor() {
        RecordingStore store = new RecordingStore(-1);
        VectorStoreService vectors = newService(store);
        IndexingScheduler.DocumentFetcher fetcher = mock(IndexingScheduler.DocumentFetcher.class);
        MemoryReinforcementService memory = mock(MemoryReinforcementService.class);
        String content = "Durably persisted indexing content with one concise factual segment.";
        when(fetcher.fetchNewDocumentsSince(any(LocalDateTime.class)))
                .thenReturn(List.of(Document.from(content)), List.of());
        IndexingScheduler scheduler = new IndexingScheduler(
                new FixedEmbeddingModel(), store, fetcher, memory, vectors);

        scheduler.scheduleIndexing();
        scheduler.scheduleIndexing();

        verify(memory, times(1)).reinforceWithSnippet(
                eq("0"), isNull(), eq(content), eq("WEB"), eq(1.0d));
        ArgumentCaptor<LocalDateTime> cursors = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fetcher, times(2)).fetchNewDocumentsSince(cursors.capture());
        assertTrue(cursors.getAllValues().get(1).isAfter(cursors.getAllValues().get(0)));
        assertEquals(1, store.addAllCalls);
    }

    @Test
    void preservesFetcherProvenanceThroughEveryChunkWithoutMutatingInput() {
        Document document = syntheticDocument();
        RecordingStore store = indexDocuments(document);

        assertTrue(store.persistedSegments.size() > 1);
        for (TextSegment segment : store.persistedSegments) {
            Map<String, Object> metadata = segment.metadata().toMap();
            assertAll(document.metadata().toMap().entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(VectorMetaKeys.META_VERIFIED))
                    .map(entry -> (org.junit.jupiter.api.function.Executable)
                            () -> assertEquals(entry.getValue(), metadata.get(entry.getKey()), entry.getKey())));
        }
        assertEquals("true", document.metadata().getString(VectorMetaKeys.META_VERIFIED));
        assertEquals("2026-01-01T00:00:00", document.metadata().getString("fetchedAt"));
    }

    @Test
    void fetcherVerifiedTrueStillRequiresIndependentVerification() {
        RecordingStore store = indexDocuments(syntheticDocument());

        assertTrue(store.persistedSegments.size() > 1);
        for (TextSegment segment : store.persistedSegments) {
            assertEquals("false", segment.metadata().getString(VectorMetaKeys.META_VERIFIED));
            assertEquals("true", segment.metadata().getString(VectorMetaKeys.META_VERIFICATION_NEEDED));
        }
    }

    @Test
    void stableSourceIdentityStagesUnverifiedChunksInShadowAndRemainsStableOnRetry() {
        RecordingStore store = new RecordingStore(-1);
        VectorStoreService vectors = newService(store);
        VectorShadowMergeDlqService dlq = mock(VectorShadowMergeDlqService.class);
        ReflectionTestUtils.setField(vectors, "shadowWriteEnabled", true);
        ReflectionTestUtils.setField(vectors, "shadowRequireExplicitId", true);
        ReflectionTestUtils.setField(vectors, "vectorShadowMergeDlqService", dlq);
        IndexingScheduler.DocumentFetcher fetcher = mock(IndexingScheduler.DocumentFetcher.class);
        when(fetcher.fetchNewDocumentsSince(any(LocalDateTime.class)))
                .thenReturn(List.of(syntheticDocument()));
        IndexingScheduler scheduler = new IndexingScheduler(new FixedEmbeddingModel(), store, fetcher,
                mock(MemoryReinforcementService.class), vectors);

        MDC.put("trace", "synthetic-indexing-run");
        scheduler.scheduleIndexing();
        List<String> firstIds = List.copyOf(store.attemptedIds.get(0));
        int chunkCount = store.persistedSegments.size();
        scheduler.scheduleIndexing();

        assertEquals(firstIds, store.attemptedIds.get(1));
        assertEquals(chunkCount, new HashSet<>(firstIds).size());
        for (TextSegment segment : store.persistedSegments) {
            assertEquals("unverified", segment.metadata().getString(VectorMetaKeys.META_SHADOW_REASON));
            assertTrue(segment.metadata().getString(VectorMetaKeys.META_SID).startsWith("0~S"));
            assertEquals("0", segment.metadata().getString(VectorMetaKeys.META_SHADOW_TARGET_SID));
            assertFalse(segment.metadata().getString(VectorMetaKeys.META_ORIGINAL_ID).isBlank());
        }
        verify(dlq, times(2 * chunkCount)).recordStaged(anyString(), anyString(), eq("0"), eq("0"),
                anyString(), anyString(), eq("unverified"), anyString(), anyMap());
    }

    @Test
    void fetcherCannotBypassShadowAdmission() {
        RecordingStore store = new RecordingStore(-1);
        VectorStoreService vectors = newService(store);
        VectorShadowMergeDlqService dlq = mock(VectorShadowMergeDlqService.class);
        ReflectionTestUtils.setField(vectors, "shadowWriteEnabled", true);
        ReflectionTestUtils.setField(vectors, "shadowRequireExplicitId", true);
        ReflectionTestUtils.setField(vectors, "vectorShadowMergeDlqService", dlq);
        Document document = syntheticDocument();
        document.metadata().put(VectorMetaKeys.META_SHADOW_BYPASS, "true");
        IndexingScheduler.DocumentFetcher fetcher = mock(IndexingScheduler.DocumentFetcher.class);
        when(fetcher.fetchNewDocumentsSince(any(LocalDateTime.class)))
                .thenReturn(List.of(document));
        IndexingScheduler scheduler = new IndexingScheduler(new FixedEmbeddingModel(), store, fetcher,
                mock(MemoryReinforcementService.class), vectors);

        MDC.put("trace", "synthetic-bypass-run");
        scheduler.scheduleIndexing();

        assertTrue(store.persistedSegments.size() > 1);
        for (TextSegment segment : store.persistedSegments) {
            assertEquals("unverified", segment.metadata().getString(VectorMetaKeys.META_SHADOW_REASON));
            assertTrue(segment.metadata().getString(VectorMetaKeys.META_SID).startsWith("0~S"));
            assertFalse(segment.metadata().toMap().containsKey(VectorMetaKeys.META_SHADOW_BYPASS));
            assertEquals("false", segment.metadata().getString(VectorMetaKeys.META_VERIFIED));
            assertEquals("true", segment.metadata().getString(VectorMetaKeys.META_VERIFICATION_NEEDED));
        }
        verify(dlq, times(store.persistedSegments.size())).recordStaged(anyString(), anyString(), eq("0"),
                eq("0"), anyString(), anyString(), eq("unverified"), anyString(), anyMap());
        assertEquals("true", document.metadata().getString(VectorMetaKeys.META_SHADOW_BYPASS));
    }

    @Test
    void sourceIdFallbackUsesExplicitIdentityAndMissingIdentityStaysUnverified() {
        VectorStoreService vectors = mock(VectorStoreService.class);
        IndexingScheduler.DocumentFetcher fetcher = mock(IndexingScheduler.DocumentFetcher.class);
        String identified = "Synthetic source ID document.";
        String anonymous = "Synthetic document without a source identifier.";
        when(fetcher.fetchNewDocumentsSince(any(LocalDateTime.class))).thenReturn(List.of(
                Document.from(identified, Metadata.from(Map.of("source_id", "fixture-source-1"))),
                Document.from(anonymous)));
        IndexingScheduler scheduler = new IndexingScheduler(new FixedEmbeddingModel(), mock(EmbeddingStore.class),
                fetcher, mock(MemoryReinforcementService.class), vectors);

        scheduler.scheduleIndexing();

        verify(vectors).enqueue(anyString(), eq("0"), eq(identified), anyMap());
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(vectors).enqueue(eq("0"), eq(anonymous), metadata.capture());
        assertEquals("false", metadata.getValue().get(VectorMetaKeys.META_VERIFIED));
        assertEquals("true", metadata.getValue().get(VectorMetaKeys.META_VERIFICATION_NEEDED));
    }

    private static Document syntheticDocument() {
        String text = java.util.stream.IntStream.range(0, 16)
                .mapToObj(i -> "Synthetic fixture paragraph " + i + " describes independently sourced provenance and safe indexing.")
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return Document.from(text, Metadata.from(Map.of(
                "url", "https://example.invalid/fixture", "revision", "fixture-r1",
                "content_hash", "synthetic-content-hash", "license", "synthetic-license",
                "owner", "synthetic-owner", "locale", "ko-KR", "consent", "fixture-consent",
                "source", "SYNTHETIC_FETCHER", "fetchedAt", "2026-01-01T00:00:00",
                VectorMetaKeys.META_VERIFIED, "true")));
    }

    private static RecordingStore indexDocuments(Document document) {
        RecordingStore store = new RecordingStore(-1);
        IndexingScheduler.DocumentFetcher fetcher = mock(IndexingScheduler.DocumentFetcher.class);
        when(fetcher.fetchNewDocumentsSince(any(LocalDateTime.class))).thenReturn(List.of(document));
        new IndexingScheduler(new FixedEmbeddingModel(), store, fetcher,
                mock(MemoryReinforcementService.class), newService(store)).scheduleIndexing();
        return store;
    }

    private static VectorStoreService newService(RecordingStore store) {
        VectorStoreService service = new VectorStoreService(new FixedEmbeddingModel(), store);
        configure(service);
        return service;
    }

    private static void configure(VectorStoreService service) {
        ReflectionTestUtils.setField(service, "batchSize", 1000);
        ReflectionTestUtils.setField(service, "shadowWriteEnabled", false);
        ReflectionTestUtils.setField(service, "flushGrouping", "trace");
        ReflectionTestUtils.setField(service, "initialBackoffMs", 60_000L);
        ReflectionTestUtils.setField(service, "maxBackoffMs", 60_000L);
    }

    private static Set<String> flattenIds(
            List<List<String>> attempts, int fromInclusive, int toExclusive) {
        Set<String> ids = new HashSet<>();
        for (List<String> attempt : attempts.subList(fromInclusive, toExclusive)) {
            ids.addAll(attempt);
        }
        return ids;
    }

    private static final class TracePerPayloadVectorStoreService extends VectorStoreService {
        private final AtomicInteger sequence = new AtomicInteger();

        private TracePerPayloadVectorStoreService(
                EmbeddingModel model, EmbeddingStore<TextSegment> store) {
            super(model, store);
        }

        @Override
        public void enqueue(String sessionId, String text, Map<String, Object> extraMeta) {
            enqueue(null, sessionId, text, extraMeta);
        }

        @Override
        public void enqueue(String explicitId, String sessionId, String text, Map<String, Object> extraMeta) {
            String oldTrace = MDC.get("traceId");
            String oldRequest = MDC.get("x-request-id");
            int group = sequence.incrementAndGet();
            MDC.put("traceId", "indexing-test-trace-" + group);
            MDC.put("x-request-id", "indexing-test-request-" + group);
            try {
                super.enqueue(explicitId, sessionId, text, extraMeta);
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
        private final int failOnAddAllCall;
        private final List<List<String>> acceptedIds = new ArrayList<>();
        private final List<List<String>> attemptedIds = new ArrayList<>();
        private final Set<String> persistedIds = new HashSet<>();
        private final List<TextSegment> persistedSegments = new ArrayList<>();
        private int addAllCalls;

        private RecordingStore(int failOnAddAllCall) {
            this.failOnAddAllCall = failOnAddAllCall;
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
            attemptedIds.add(List.copyOf(ids));
            if (addAllCalls == failOnAddAllCall) {
                throw new IllegalStateException("deterministic store failure");
            }
            acceptedIds.add(List.copyOf(ids));
            persistedIds.addAll(ids);
            persistedSegments.addAll(embedded);
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            return new EmbeddingSearchResult<>(List.of());
        }
    }
}
