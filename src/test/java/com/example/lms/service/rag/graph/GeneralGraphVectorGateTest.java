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
    private VectorStoreService service(EmbeddingModel model, EmbeddingStore<TextSegment> store) {
        var service = new VectorStoreService(model, store);
        ReflectionTestUtils.setField(service, "batchSize", 100);
        ReflectionTestUtils.setField(service, "queueMaxPending", 1000);
        ReflectionTestUtils.setField(service, "generalGraphVectorGate", gate);
        return service;
    }
}
