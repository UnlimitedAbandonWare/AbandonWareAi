package com.example.lms.api;

import com.example.lms.api.dto.SearchFeedbackDto;
import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatSession;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.rag.feedback.FeedbackBlocklistRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchFeedbackControllerInputTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void feedbackRejectsNullPayloadWithoutTouchingRegistry() {
        try (var fixture = new Fixture()) {
            var response = fixture.controller.feedback(null);

            assertEquals(400, response.getStatusCode().value());
            assertEquals(Boolean.TRUE, TraceStore.get("api.searchFeedback.rejected"));
            assertEquals(1L, TraceStore.get("api.searchFeedback.rejected.count"));
            assertEquals("missing_payload", TraceStore.get("api.searchFeedback.skipped.reason"));
            verifyNoInteractions(fixture.registry, fixture.history, fixture.owners);
        }
    }

    @Test
    void foreignOwnerCannotPoisonTheSameSessionBlocklist() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.history.getSessionForRequest(7L)).thenReturn(anonymousSession(7L, "owner-a"));
            when(fixture.owners.ownerKey()).thenReturn("owner-b");

            fixture.send("7", null).andExpect(status().isForbidden());
            fixture.send("chat-7", null).andExpect(status().isForbidden());

            verifyNoInteractions(fixture.registry);
            verify(fixture.history, never()).getSessionWithMessages(anyLong());
            verify(fixture.history, never()).getSessionWithMessages(anyLong(), anyInt());
        }
    }

    @Test
    void ownSessionMayMutateOnlyItsExistingBlocklistKey() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.history.getSessionForRequest(7L)).thenReturn(anonymousSession(7L, "owner-a"));
            when(fixture.owners.ownerKey()).thenReturn("owner-a");

            fixture.send("chat-7", null).andExpect(status().isOk());

            verify(fixture.registry).downvote("chat-7", "source.example.test", "synthetic title",
                    "https://source.example.test/result", "synthetic reason");
            verifyNoMoreInteractions(fixture.registry);
            verify(fixture.history, never()).getSessionForRequest(8L);
        }
    }

    @Test
    void missingInvalidAndOverflowSessionIdsFailClosedBeforeLookup() throws Exception {
        try (var fixture = new Fixture()) {
            for (String session : List.of("", "0", "-1", "not-a-session", "chat-0", "9223372036854775808")) {
                fixture.send(session, null).andExpect(status().isBadRequest());
            }
            SearchFeedbackDto missing = new SearchFeedbackDto();
            missing.setAction("downvote");
            assertEquals(400, fixture.controller.feedback(missing).getStatusCode().value());
            verifyNoInteractions(fixture.registry, fixture.history, fixture.owners);
        }
    }

    @Test
    void unknownSessionNeverCreatesABlocklist() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.send("99", null).andExpect(status().isNotFound());
            verifyNoInteractions(fixture.registry, fixture.owners);
        }
    }

    @Test
    void authenticatedSessionRequiresItsExactUserAndIgnoresCookieClaim() throws Exception {
        try (var fixture = new Fixture()) {
            doReturn(administratorSession(7L, "user-a")).when(fixture.history).getSessionForRequest(7L);
            when(fixture.owners.ownerKey()).thenReturn("owner-a");

            fixture.send("7", new TestingAuthenticationToken("user-b", "unused", "ROLE_ADMIN"))
                    .andExpect(status().isForbidden());
            fixture.send("7", new TestingAuthenticationToken("user-a", "unused"))
                    .andExpect(status().isForbidden());
            fixture.send("7", new TestingAuthenticationToken("user-a", "unused", "ROLE_USER"))
                    .andExpect(status().isOk());

            verify(fixture.registry, times(1)).downvote(anyString(), anyString(), anyString(), anyString(), anyString());
            verifyNoInteractions(fixture.owners);
        }
    }

    @Test
    void prototypeAndAdminTokenPrincipalsCannotImpersonateAStoredUser() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.owners.ownerKey()).thenReturn("owner-b");
            for (String principal : List.of("proto-open", "admin-token")) {
                doReturn(administratorSession(7L, principal)).when(fixture.history).getSessionForRequest(7L);
                fixture.send("7", new TestingAuthenticationToken(principal, "unused", "ROLE_ADMIN"))
                        .andExpect(status().isForbidden());
            }
            doReturn(administratorSession(7L, "anonymousUser")).when(fixture.history).getSessionForRequest(7L);
            fixture.send("7", new AnonymousAuthenticationToken("synthetic-key", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(fixture.registry);
        }
    }

    @Test
    void anonymousPrototypeCallerStillMayDownvoteItsOwnSession() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.history.getSessionForRequest(7L)).thenReturn(anonymousSession(7L, "owner-a"));
            when(fixture.owners.ownerKey()).thenReturn("owner-a");
            fixture.send("7", new TestingAuthenticationToken("proto-open", "unused", "ROLE_ADMIN"))
                    .andExpect(status().isOk());
            verify(fixture.registry).downvote(anyString(), anyString(), anyString(), anyString(), anyString());
        }
    }

    @Test
    void unavailableOwnerOrSessionLookupNeverMutatesRegistry() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.history.getSessionForRequest(7L)).thenThrow(new IllegalStateException("synthetic unavailable"));
            fixture.send("7", null).andExpect(status().isServiceUnavailable());
            doReturn(anonymousSession(7L, "owner-a")).when(fixture.history).getSessionForRequest(7L);
            when(fixture.owners.ownerKey()).thenThrow(new IllegalStateException("synthetic unavailable"));
            fixture.send("7", null).andExpect(status().isServiceUnavailable());
            verifyNoInteractions(fixture.registry);
        }
    }

    private static ChatSession anonymousSession(long id, String ownerKey) {
        ChatSession session = new ChatSession("synthetic feedback", ownerKey, "ANON");
        session.setId(id);
        return session;
    }

    private static ChatSession administratorSession(long id, String username) {
        Administrator administrator = mock(Administrator.class);
        when(administrator.getUsername()).thenReturn(username);
        ChatSession session = new ChatSession("synthetic feedback", administrator);
        session.setId(id);
        return session;
    }

    private static final class Fixture implements AutoCloseable {
        final FeedbackBlocklistRegistry registry = mock(FeedbackBlocklistRegistry.class);
        final ChatHistoryService history = mock(ChatHistoryService.class);
        final ClientOwnerKeyResolver owners = mock(ClientOwnerKeyResolver.class);
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final SearchFeedbackController controller;
        final MockMvc mvc;

        Fixture() {
            context.registerBean(FeedbackBlocklistRegistry.class, () -> registry);
            context.registerBean(ChatHistoryService.class, () -> history);
            context.registerBean(ClientOwnerKeyResolver.class, () -> owners);
            context.registerBean(SearchFeedbackController.class);
            context.refresh();
            controller = context.getBean(SearchFeedbackController.class);
            mvc = MockMvcBuilders.standaloneSetup(controller).build();
        }

        org.springframework.test.web.servlet.ResultActions send(String session, Authentication authentication) throws Exception {
            var request = post("/api/search/feedback").contentType("application/json")
                    .content("{\"sessionId\":\"" + session + "\",\"query\":\"synthetic query\","
                            + "\"host\":\"source.example.test\",\"title\":\"synthetic title\","
                            + "\"url\":\"https://source.example.test/result\",\"reason\":\"synthetic reason\",\"action\":\"downvote\"}");
            if (authentication != null) request.principal(authentication);
            return mvc.perform(request);
        }

        @Override public void close() { context.close(); }
    }
}
