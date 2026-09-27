package com.example.lms.service.rag.graph;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.QueryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneralGraphScopeTest {
    private static ChatSession guest(long id, String owner) {
        ChatSession session = new ChatSession("synthetic", owner, "ANON");
        session.setId(id);
        return session;
    }

    @Test
    void guestScopeRequiresThePersistedOwnerAndSession() {
        ChatSession session = guest(7L, "synthetic-owner-a");
        var scope = GeneralGraphScope.authorize(session, "anonymousUser", "synthetic-owner-a").orElseThrow();
        assertEquals(AttachmentOwnerIdentity.forAnonymous("synthetic-owner-a").hash(), scope.ownerNamespace());
        assertEquals("GENERAL", scope.channel());
        assertTrue(scope.matchesSession(7L));
        assertFalse(scope.matchesSession(8L));
        assertTrue(GeneralGraphScope.authorize(session, "anonymousUser", "synthetic-owner-b").isEmpty());
        assertTrue(GeneralGraphScope.authorize(guest(7L, null), "anonymousUser", null).isEmpty());
        assertTrue(GeneralGraphScope.authorize(guest(0L, "synthetic-owner-a"), "anonymousUser", "synthetic-owner-a").isEmpty());
    }

    @Test
    void administratorAndAnonymousNamespacesCannotAlias() {
        Administrator owner = new Administrator();
        owner.setUsername("synthetic-admin");
        ChatSession session = new ChatSession("synthetic", owner);
        session.setId(8L);
        assertTrue(GeneralGraphScope.authorize(session, "synthetic-admin", null).isPresent());
        assertTrue(GeneralGraphScope.authorize(session, "anonymousUser", "synthetic-admin").isEmpty());
        assertTrue(GeneralGraphScope.authorize(session, "other-admin", null).isEmpty());
    }

    @Test
    void sameOwnerDoesNotGrantAnotherSession() {
        var first = GeneralGraphScope.authorize(guest(7L, "owner"), null, "owner").orElseThrow();
        var second = GeneralGraphScope.authorize(guest(8L, "owner"), null, "owner").orElseThrow();
        assertEquals(first.ownerNamespace(), second.ownerNamespace());
        assertFalse(first.matchesSession(second.sessionId()));
        assertFalse(first.toString().contains(first.ownerNamespace()));
    }

    @Test
    void publicJsonCannotSupplyOrReadGraphAuthority() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatRequestDto forged = mapper.readValue(
                "{\"sessionId\":7,\"generalGraphScope\":{\"ownerNamespace\":\"forged\",\"sessionId\":7}}",
                ChatRequestDto.class);
        assertNull(forged.getGeneralGraphScope());
        var scope = GeneralGraphScope.authorize(guest(7L, "owner"), null, "owner").orElseThrow();
        forged.bindGeneralGraphScope(scope);
        assertFalse(mapper.writeValueAsString(forged).contains("generalGraphScope"));
        assertFalse(mapper.writeValueAsString(forged).contains(scope.ownerNamespace()));
        assertSame(scope, forged.toBuilder().build().getGeneralGraphScope());
    }

    @Test
    void queryRewritePreservesAuthorityButChangedSessionRejectsIt() {
        var scope = GeneralGraphScope.authorize(guest(7L, "owner"), null, "owner").orElseThrow();
        var query = QueryUtils.buildQuery("synthetic", 7L, null,
                Map.of(GeneralGraphScope.METADATA_KEY, scope));
        assertSame(scope, QueryUtils.generalGraphScope(QueryUtils.rebuild(query, "rewritten")).orElseThrow());
        assertTrue(QueryUtils.generalGraphScope(QueryUtils.rebuild(query, "rewritten",
                Map.of(LangChainRAGService.META_SID, 8L))).isEmpty());
        assertTrue(QueryUtils.generalGraphScope(QueryUtils.buildQuery("synthetic", 7L, null,
                Map.of(GeneralGraphScope.METADATA_KEY, Map.of("ownerNamespace", scope.ownerNamespace())))).isEmpty());
        assertTrue(QueryUtils.generalGraphScope(QueryUtils.buildQuery("synthetic", 7L, null)).isEmpty());
    }
}
