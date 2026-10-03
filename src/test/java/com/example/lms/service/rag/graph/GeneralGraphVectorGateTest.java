package com.example.lms.service.rag.graph;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.domain.enums.MemoryProfile;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.service.VectorStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GeneralGraphVectorGateTest {
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ChatSession session = new ChatSession("synthetic", "owner", "ANON");
    private final GeneralGraphSourceAuthority authority = new GeneralGraphSourceAuthority(sessions, messages, new ObjectMapper());
    private final GeneralGraphVectorGate gate = new GeneralGraphVectorGate(authority);
    private final ChatMessage message = new ChatMessage(session, "user", "Spark was considered, not purchased");

    private GeneralGraphScope scope() {
        session.setId(7L); message.setId(11L);
        when(sessions.findByIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(messages.findById(11L)).thenReturn(Optional.of(message));
        return authority.bindPolicy(GeneralGraphScope.authorize(session, null, "owner").orElseThrow(), null).orElseThrow();
    }
    private Map<String, Object> meta(GeneralGraphScope scope) {
        return new LinkedHashMap<>(Map.of(
                "doc_type", "BRAIN_STATE", "ingest_lane", "brain_state",
                "general_graph_private", "true", "general_graph_owner_namespace", scope.ownerNamespace(),
                "general_graph_session_id", scope.sessionId(), "general_graph_consent_epoch", scope.consentEpoch(),
                "general_graph_source_id", "chat-message:11",
                "general_graph_source_revision", authority.source(scope, 11L).orElseThrow().sourceRevision()));
    }
    @Test void offCorrectionDeletionAndForeignScopeAreRejected() {
        var scope = scope(); var meta = meta(scope); var writes = new AtomicInteger();
        assertFalse(GeneralGraphVectorGate.commit(gate, "8", meta, writes::incrementAndGet));
        assertTrue(GeneralGraphVectorGate.commit(gate, "7", meta, writes::incrementAndGet));
        message.setContent("corrected original");
        assertFalse(GeneralGraphVectorGate.commit(gate, "7", meta, writes::incrementAndGet));
        message.setContent("Spark was considered, not purchased");
        authority.bindPolicy(scope, MemoryProfile.OFF);
        authority.bindPolicy(scope, MemoryProfile.LIGHT);
        assertFalse(GeneralGraphVectorGate.commit(gate, "7", meta, writes::incrementAndGet));
        when(messages.findById(11L)).thenReturn(Optional.empty());
        assertFalse(GeneralGraphVectorGate.commit(gate, "7", meta, writes::incrementAndGet));
        assertEquals(1, writes.get());
    }
    @Test void promptEntryUsesCurrentOriginalNotStoredVectorPayload() {
        var scope = scope(); var meta = meta(scope);
        var segment = TextSegment.from("incorrect synthetic vector payload", Metadata.from(meta));
        var content = GeneralGraphVectorGate.content(gate, scope, segment).orElseThrow();
        assertTrue(content.textSegment().text().contains("not purchased"));
        assertFalse(content.textSegment().text().contains("incorrect synthetic vector payload"));
        assertTrue(GeneralGraphVectorGate.content(gate, null, segment).isEmpty());
        message.setContent("corrected");
        assertTrue(GeneralGraphVectorGate.content(gate, scope, segment).isEmpty());
    }
    @Test void unknownLegacyAndMalformedPrivateRowsCannotBecomePublic() {
        var writes = new AtomicInteger();
        assertFalse(GeneralGraphVectorGate.commit(gate, "7", Map.of("doc_type", "BRAIN_STATE"), writes::incrementAndGet));
        assertFalse(GeneralGraphVectorGate.commit(gate, "global", Map.of("kb_confidence", .9), writes::incrementAndGet));
        var manual = new LinkedHashMap<String, Object>(Map.of("doc_type", "GRAPHDB_MANUAL_LEARNING",
                "ingest_lane", "graphdb_manual_learning"));
        assertTrue(GeneralGraphVectorGate.commit(null, "global", manual, writes::incrementAndGet));
        manual.put("general_graph_private", "invalid");
        assertFalse(GeneralGraphVectorGate.commit(gate, "global", manual, writes::incrementAndGet));
        assertEquals(1, writes.get());
    }
    @Test void sourceRowIsLockedAndRefreshedBeforeTheCommitCallback() {
        var scope = scope(); var evidence = authority.source(scope, 11L).orElseThrow();
        var manager = mock(jakarta.persistence.EntityManager.class);
        ReflectionTestUtils.setField(authority, "entityManager", manager);
        doAnswer(invocation -> { message.setContent("corrected during lock acquisition"); return null; })
                .when(manager).refresh(message, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var writes = new AtomicInteger();
        assertTrue(authority.withCurrentSource(scope, evidence, e -> writes.incrementAndGet()).isEmpty());
        verify(manager).refresh(message, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        assertEquals(0, writes.get());
    }
    @Test void queueDropsInvalidatedEntriesBeforeEmbeddingWithoutRequeue() {
        var scope = scope(); var meta = meta(scope);
        var model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        var service = service(model, store);
        service.enqueue("synthetic-private", "7", message.getContent(), meta);
        authority.bindPolicy(scope, MemoryProfile.OFF);
        var result = service.flush();
        assertFalse(result.durable());
        assertEquals("source_rejected", result.reasonCode());
        assertEquals(0, result.succeededCount());
        assertEquals(0, result.pendingCount());
        assertEquals(0, service.bufferStats().inFlight());
        verifyNoInteractions(model, store);
    }
    @Test void revocationDuringEmbeddingCannotCommitOrRequeueOldContent() {
        var scope = scope(); var meta = meta(scope);
        var model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(call -> {
            authority.bindPolicy(scope, MemoryProfile.OFF);
            return Response.from(List.of(Embedding.from(new float[]{1})));
        });
        var service = service(model, store);
        service.enqueue("synthetic-private", "7", message.getContent(), meta);
        var result = service.flush();
        assertEquals("source_rejected", result.reasonCode());
        assertEquals(0, result.succeededCount());
        assertEquals(0, service.pendingSize());
        verifyNoInteractions(store);
    }
    @Test void mixedProtectedBatchComputesOnceAndPreservesEveryIdVectorPair() {
        var scope = scope(); var privateMeta = meta(scope);
        var model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(call -> {
            List<TextSegment> segments = call.getArgument(0);
            return Response.from(segments.stream().map(s ->
                    Embedding.from(new float[]{s.text().charAt(s.text().length() - 1)})).toList());
        });
        Map<String, String> expected = Map.of("private-a", "payload-a",
                "private-b", "payload-b", "public-c", "payload-c");
        Set<String> stored = new HashSet<>();
        doAnswer(call -> {
            List<String> ids = call.getArgument(0);
            List<Embedding> vectors = call.getArgument(1);
            List<TextSegment> segments = call.getArgument(2);
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i), text = segments.get(i).text();
                assertEquals(expected.get(id), text);
                assertEquals((float) text.charAt(text.length() - 1), vectors.get(i).vector()[0]);
                assertTrue(stored.add(id));
                assertEquals(segments.get(0).metadata().toMap().get("general_graph_source_id"),
                        segments.get(i).metadata().toMap().get("general_graph_source_id"));
            }
            return null;
        }).when(store).addAll(anyList(), anyList(), anyList());
        var service = service(model, store);
        service.enqueue("private-b", "7", "payload-b", privateMeta);
        service.enqueue("public-c", "7", "payload-c", Map.of());
        service.enqueue("private-a", "7", "payload-a", privateMeta);
        service.enqueue("private-a", "7", "payload-a", privateMeta); // same deterministic ID
        var result = service.flush();
        assertTrue(result.durable());
        assertEquals(3, result.succeededCount());
        assertEquals(expected.keySet(), stored);
        assertEquals(0, service.bufferStats().inFlight());
        verify(model, times(1)).embedAll(argThat(segments -> segments.size() == 3));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"revoke", "correct", "delete"})
    void batchRechecksEachProtectedSourceAfterEmbedding(String mutation) {
        var scope = scope(); var privateMeta = meta(scope);
        var model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(call -> {
            List<TextSegment> segments = call.getArgument(0);
            switch (mutation) {
                case "revoke" -> authority.bindPolicy(scope, MemoryProfile.OFF);
                case "correct" -> message.setContent("corrected original");
                case "delete" -> when(messages.findById(11L)).thenReturn(Optional.empty());
                default -> fail("unknown fixture");
            }
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1})).toList());
        });
        var service = service(model, store);
        service.enqueue("private-a", "7", "payload-a", privateMeta);
        service.enqueue("private-b", "7", "payload-b", privateMeta);
        var result = service.flush();
        assertEquals("source_rejected", result.reasonCode());
        assertEquals(0, result.succeededCount());
        assertEquals(0, service.pendingSize());
        assertEquals(0, service.bufferStats().inFlight());
        verifyNoInteractions(store);
    }

    @Test void partialProtectedStoreFailureMarksOnlyCommittedIdsAndRequeuesTheRest() {
        var scope = scope(); var privateMeta = meta(scope);
        var model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(call -> {
            List<TextSegment> segments = call.getArgument(0);
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1})).toList());
        });
        AtomicInteger writes = new AtomicInteger();
        doAnswer(call -> {
            if (writes.incrementAndGet() == 2) throw new IllegalStateException("synthetic-store-failure");
            return null;
        }).when(store).addAll(anyList(), anyList(), anyList());
        var service = service(model, store);
        for (int i = 0; i < 3; i++) service.enqueue("private-" + i, "7", "payload-" + i, distinctSourceMeta(scope, 12L + i));
        var result = service.flush();
        assertEquals("store_failure", result.reasonCode());
        assertEquals(1, result.succeededCount());
        assertEquals(2, result.pendingCount());
        assertEquals(0, service.bufferStats().inFlight());
        verify(model, times(1)).embedAll(argThat(segments -> segments.size() == 3));
    }

    @Test void malformedProtectedBatchResponseRequeuesWithoutAnyStoreWrite() {
        var scope = scope(); var privateMeta = meta(scope);
        var model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of()));
        var service = service(model, store);
        service.enqueue("private-a", "7", "payload-a", privateMeta);
        service.enqueue("private-b", "7", "payload-b", privateMeta);
        var result = service.flush();
        assertEquals("store_failure", result.reasonCode());
        assertEquals(0, result.succeededCount());
        assertEquals(2, result.pendingCount());
        assertEquals(0, service.bufferStats().inFlight());
        verifyNoInteractions(store);
    }

    @Test void sameSourceBatchSharesOneFencedStoreAndPreservesAlignment() {
        var scope = scope(); var privateMeta = meta(scope);
        var model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(call -> {
            List<TextSegment> segments = call.getArgument(0);
            return Response.from(segments.stream().map(s ->
                    Embedding.from(new float[]{1 + Integer.parseInt(s.text().substring(8))})).toList());
        });
        doAnswer(call -> {
            List<String> ids = call.getArgument(0);
            List<Embedding> vectors = call.getArgument(1);
            List<TextSegment> segments = call.getArgument(2);
            assertEquals(32, ids.size());
            for (int i = 0; i < ids.size(); i++) {
                String suffix = ids.get(i).substring(8);
                assertEquals("payload-" + suffix, segments.get(i).text());
                assertEquals(1 + Float.parseFloat(suffix), vectors.get(i).vector()[0]);
            }
            return null;
        }).when(store).addAll(anyList(), anyList(), anyList());
        var service = service(model, store);
        for (int i = 0; i < 32; i++) service.enqueue("private-" + i, "7", "payload-" + i, privateMeta);
        var result = service.flush();
        assertTrue(result.durable());
        assertEquals(32, result.succeededCount());
        assertEquals(0, service.bufferStats().inFlight());
        verify(model).embedAll(argThat(segments -> segments.size() == 32));
        verify(store).addAll(argThat(ids -> ids.size() == 32), anyList(), anyList());
    }

    @Test void distinctSourcesNeverShareOneCommitEvenWithinSameSession() {
        var scope = scope();
        var model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(call -> {
            List<TextSegment> segments = call.getArgument(0);
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1})).toList());
        });
        var service = service(model, store);
        for (int i = 0; i < 2; i++) {
            service.enqueue("private-" + i, "7", "payload-" + i, distinctSourceMeta(scope, 12L + i));
        }
        var result = service.flush();
        assertEquals(2, result.succeededCount());
        verify(store, times(2)).addAll(argThat(ids -> ids.size() == 1), anyList(), anyList());
        verify(model).embedAll(argThat(segments -> segments.size() == 2));
    }

    @Test void ambiguousSameSourceStoreFailureRetriesTheSameIdsWithoutFalseDurableMarking() {
        var scope = scope(); var privateMeta = meta(scope);
        var model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(call -> {
            List<TextSegment> segments = call.getArgument(0);
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1})).toList());
        });
        Map<String, TextSegment> durable = new HashMap<>();
        AtomicInteger calls = new AtomicInteger();
        List<List<String>> attempts = new ArrayList<>();
        doAnswer(call -> {
            List<String> ids = call.getArgument(0);
            List<TextSegment> segments = call.getArgument(2);
            attempts.add(List.copyOf(ids));
            durable.put(ids.get(0), segments.get(0));
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("ambiguous synthetic partial write");
            for (int i = 0; i < ids.size(); i++) durable.put(ids.get(i), segments.get(i));
            return null;
        }).when(store).addAll(anyList(), anyList(), anyList());
        var service = service(model, store);
        for (int i = 0; i < 3; i++) service.enqueue("private-" + i, "7", "payload-" + i, privateMeta);
        var failed = service.flush();
        assertEquals("store_failure", failed.reasonCode());
        assertEquals(0, failed.succeededCount());
        assertEquals(3, failed.pendingCount());
        assertEquals(0, service.bufferStats().inFlight());
        ReflectionTestUtils.setField(service, "backoffUntilEpochMs", 0L);
        var retry = service.flush();
        assertTrue(retry.durable());
        assertEquals(3, retry.succeededCount());
        assertEquals(Set.of("private-0", "private-1", "private-2"), durable.keySet());
        assertEquals(new HashSet<>(attempts.get(0)), new HashSet<>(attempts.get(1)));
    }

    private Map<String, Object> distinctSourceMeta(GeneralGraphScope scope, long sourceId) {
        var source = new ChatMessage(session, "user", "source-" + sourceId);
        source.setId(sourceId);
        when(messages.findById(sourceId)).thenReturn(Optional.of(source));
        var metadata = meta(scope);
        metadata.put("general_graph_source_id", "chat-message:" + sourceId);
        metadata.put("general_graph_source_revision", authority.source(scope, sourceId).orElseThrow().sourceRevision());
        return metadata;
    }

    private VectorStoreService service(EmbeddingModel model, EmbeddingStore<TextSegment> store) {
        var service = new VectorStoreService(model, store);
        ReflectionTestUtils.setField(service, "batchSize", 100);
        ReflectionTestUtils.setField(service, "flushGrouping", "session");
        ReflectionTestUtils.setField(service, "queueMaxPending", 1000);
        ReflectionTestUtils.setField(service, "generalGraphVectorGate", gate);
        return service;
    }
}
