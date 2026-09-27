package com.example.lms.service.rag.graph;

import com.example.lms.assist.MemoryEvidence;
import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.domain.enums.MemoryProfile;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneralGraphSourceAuthorityTest {
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeneralGraphSourceAuthority authority = new GeneralGraphSourceAuthority(sessions, messages, mapper);
    private final ChatSession session = new ChatSession("synthetic", "owner", "ANON");

    private GeneralGraphScope authorized() {
        session.setId(7L);
        when(sessions.findByIdForUpdate(7L)).thenReturn(Optional.of(session));
        return GeneralGraphScope.authorize(session, null, "owner").orElseThrow();
    }

    private ChatMessage message(long id, String role, String text) {
        ChatMessage message = new ChatMessage(session, role, text);
        message.setId(id);
        when(messages.findById(id)).thenReturn(Optional.of(message));
        return message;
    }

    @Test
    void offAndBackOnDoNotReauthorizeAnOldTask() {
        var initial = authorized();
        var before = authority.bindPolicy(initial, MemoryProfile.LIGHT).orElseThrow();
        message(11, "assistant", "synthetic hypothesis");
        MemoryEvidence old = authority.source(before, 11).orElseThrow();
        var off = authority.bindPolicy(initial, MemoryProfile.OFF).orElseThrow();
        var after = authority.bindPolicy(initial, MemoryProfile.LIGHT).orElseThrow();
        AtomicInteger writes = new AtomicInteger();
        assertFalse(off.memoryEnabled());
        assertTrue(before.consentEpoch() < off.consentEpoch());
        assertTrue(off.consentEpoch() < after.consentEpoch());
        assertTrue(authority.withCurrentSource(before, old, e -> writes.incrementAndGet()).isEmpty());
        assertTrue(authority.source(off, 11).isEmpty());
        assertEquals(0, writes.get());
        assertTrue(authority.source(after, 11).isPresent());
        assertEquals(after.consentEpoch(), authority.bindPolicy(initial, null).orElseThrow().consentEpoch());
    }

    @Test
    void correctedOrDeletedOriginalCannotCommitTheOldEvidence() {
        var scope = authority.bindPolicy(authorized(), null).orElseThrow();
        ChatMessage original = message(11, "user", "Spark was purchased");
        MemoryEvidence old = authority.source(scope, 11).orElseThrow();
        original.setContent("Spark was considered but not purchased");
        MemoryEvidence corrected = authority.source(scope, 11).orElseThrow();
        assertNotEquals(old.sourceRevision(), corrected.sourceRevision());
        assertEquals("USER_REPORTED", corrected.assertionType());
        AtomicInteger writes = new AtomicInteger();
        assertTrue(authority.withCurrentSource(scope, old, e -> writes.incrementAndGet()).isEmpty());
        when(messages.findById(11L)).thenReturn(Optional.empty());
        assertTrue(authority.withCurrentSource(scope, corrected, e -> writes.incrementAndGet()).isEmpty());
        assertEquals(0, writes.get());
    }

    @Test
    void missingSessionAndForeignSourceFailClosed() {
        var scope = authority.bindPolicy(authorized(), null).orElseThrow();
        ChatMessage foreign = message(11, "assistant", "synthetic");
        ChatSession other = new ChatSession("other", "other-owner", "ANON");
        other.setId(8L);
        foreign.setSession(other);
        assertTrue(authority.source(scope, 11).isEmpty());
        when(sessions.findByIdForUpdate(7L)).thenReturn(Optional.empty());
        assertTrue(authority.source(scope, 11).isEmpty());
    }

    @Test
    void assistantTextIsEvidenceOfAnAssistantStatementOnly() {
        var scope = authority.bindPolicy(authorized(), null).orElseThrow();
        message(11, "assistant", "The power supply might be the cause.");
        MemoryEvidence evidence = authority.source(scope, 11).orElseThrow();
        assertEquals("ASSISTANT_GENERATED", evidence.assertionType());
        assertEquals("ASSISTANT", evidence.sourceRole());
        assertEquals("CO_MENTIONED_WITH", evidence.relationshipType());
        assertEquals("chat-message:11", evidence.sourceId());
        message(12, "system", "synthetic metadata");
        assertTrue(authority.source(scope, 12).isEmpty());
    }

    @Test
    void existingMetadataWritersRetainTheLockedEpoch() throws Exception {
        authorized();
        session.setSessionMeta("{\"generalGraphConsentEpoch\":9,\"model\":\"previous\"}");
        var history = new com.example.lms.service.ChatHistoryServiceImpl(
                sessions, messages, mock(com.example.lms.repository.AdministratorRepository.class),
                mapper, mock(com.example.lms.web.ClientOwnerKeyResolver.class));
        var entityManager = mock(jakarta.persistence.EntityManager.class);
        org.springframework.test.util.ReflectionTestUtils.setField(history, "entityManager", entityManager);
        history.updateSessionMeta(7L, Map.of("model", "next", GeneralGraphSourceAuthority.CONSENT_EPOCH_KEY, 1L));
        history.updateSessionAnswerModeAndTrace(7L, "fact", 22L);
        Map<String, Object> saved = GeneralGraphSourceAuthority.readMetadata(mapper, session.getSessionMeta());
        assertEquals(9, saved.get(GeneralGraphSourceAuthority.CONSENT_EPOCH_KEY));
        assertEquals("next", saved.get("model"));
        assertEquals(22, saved.get("lastTraceTurnId"));
        verify(sessions, times(2)).findByIdForUpdate(7L);
        verify(entityManager, times(2)).refresh(session, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void staleMetadataCannotEraseOrForgeConsentEpoch() {
        var preserved = GeneralGraphSourceAuthority.preserveEpoch(
                Map.of(GeneralGraphSourceAuthority.CONSENT_EPOCH_KEY, 9L),
                Map.of(GeneralGraphSourceAuthority.CONSENT_EPOCH_KEY, 2L, "model", "synthetic"));
        assertEquals(9L, preserved.get(GeneralGraphSourceAuthority.CONSENT_EPOCH_KEY));
        assertEquals("synthetic", preserved.get("model"));
        assertFalse(GeneralGraphSourceAuthority.preserveEpoch(Map.of(),
                Map.of(GeneralGraphSourceAuthority.CONSENT_EPOCH_KEY, 99L))
                .containsKey(GeneralGraphSourceAuthority.CONSENT_EPOCH_KEY));
        session.setSessionMeta("{broken");
        assertThrows(IllegalArgumentException.class, () -> authority.bindPolicy(authorized(), null));
    }
}
