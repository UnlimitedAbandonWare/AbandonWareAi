package com.example.lms.api;

import com.example.lms.config.ChatDefaultsProperties;
import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.*;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatSettingsEntryOrderTest {
    @AfterEach void clearContext() {
        com.example.lms.search.TraceStore.clear();
        com.example.lms.trace.TraceContext.cleanupCurrentThread();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test void syncBudgetAndExecutionUseOnePersonalSnapshot() {
        try (Fixture f = new Fixture()) {
            var budgets = new ArrayList<Integer>();
            var executions = new ArrayList<Integer>();
            doAnswer(call -> {
                budgets.add(((ChatRequestDto) call.getArgument(0)).getMaxTokens());
                return null;
            }).when(f.budget).validateChatEffective(any());
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(call -> {
                executions.add(((ChatRequestDto) call.getArgument(0)).getMaxTokens());
                return ChatResult.of("synthetic answer", "fixture-model", false);
            });
            var response = f.controller.chatSync(f.request(), null, new MockHttpServletRequest());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(budgets.isEmpty());
            assertTrue(budgets.stream().allMatch(tokens -> tokens == 128), budgets.toString());
            assertEquals(List.of(128), executions);
            verify(f.preferences, times(1)).read(anyString());
        }
    }

    @Test void streamCapturesPersonalSettingsBeforeFirstEffectiveBudgetCheck() {
        try (Fixture f = new Fixture()) {
            var budgets = new ArrayList<Integer>();
            doAnswer(call -> {
                budgets.add(((ChatRequestDto) call.getArgument(0)).getMaxTokens());
                throw new BudgetObserved();
            }).when(f.budget).validateChatEffective(any());
            assertThrows(BudgetObserved.class, () -> f.controller.chatStream(
                    f.request(), false, false, null, new MockHttpServletRequest()));
            assertEquals(List.of(128), budgets);
            verify(f.preferences, times(1)).read(anyString());
            verifyNoInteractions(f.chat);
        }
    }

    @Test void exactAttachDoesNotReadPersonalSettingsOrStartGeneration() {
        try (Fixture f = new Fixture()) {
            f.controller.chatStream(f.request(), true, false, null, new MockHttpServletRequest())
                    .collectList().block();
            verifyNoInteractions(f.preferences, f.chat);
            verify(f.budget, never()).validateChatEffective(any());
        }
    }

    private static final class BudgetObserved extends AssertionError {}

    private static final class Fixture implements AutoCloseable {
        final ChatHistoryService history = mock(ChatHistoryService.class);
        final ChatService chat = mock(ChatService.class);
        final ChatPreferenceService preferences = mock(ChatPreferenceService.class);
        final PublicRequestBudgetGuard budget = mock(PublicRequestBudgetGuard.class);
        final ChatRunRegistry registry = new ChatRunRegistry();
        final ChatApiController controller;
        Fixture() {
            ReflectionTestUtils.setField(registry, "replayCapacity", 32);
            ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
            SettingsService settings = mock(SettingsService.class);
            ClientOwnerKeyResolver owners = mock(ClientOwnerKeyResolver.class);
            when(settings.getAllSettings()).thenReturn(Map.of());
            when(settings.getChatAdminOverrides()).thenReturn(Map.of());
            when(owners.ownerKey()).thenReturn("00000000-0000-0000-0000-000000000001");
            when(preferences.read(anyString())).thenReturn(
                    new ChatPreferenceService.State(Map.of("maxTokens", 128), 1, "a".repeat(64)),
                    new ChatPreferenceService.State(Map.of("maxTokens", 256), 2, "b".repeat(64)));
            controller = new ChatApiController(history, chat, null, settings, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    new com.fasterxml.jackson.databind.ObjectMapper(), null, registry, owners);
            var factory = new ChatDefaultsProperties();
            factory.setModel("fixture-model"); factory.setModelSelectionMode("preferred");
            factory.setTemperature(0.2); factory.setTopP(1.0);
            factory.setFrequencyPenalty(0.0); factory.setPresencePenalty(0.0);
            factory.setMaxTokens(2048); factory.setUseRag(false); factory.setUseWebSearch(false);
            factory.setSearchMode("OFF"); factory.setRagAnswerPolicy("adaptive");
            factory.setDefaultsVersion("fixture");
            ReflectionTestUtils.setField(controller, "chatDefaultsProperties", factory);
            ReflectionTestUtils.setField(controller, "chatPreferenceService", preferences);
            ReflectionTestUtils.setField(controller, "publicRequestBudgetGuard", budget);
            var session = new ChatSession("fixture", "00000000-0000-0000-0000-000000000001", "ANON");
            session.setId(42L);
            when(history.getSessionWithMessages(42L)).thenReturn(session);
            when(history.getSessionForRequest(42L)).thenReturn(session);
            when(history.getSessionWithMessages(42L, 1)).thenReturn(session);
        }
        ChatRequestDto request() {
            return ChatRequestDto.builder().message("synthetic settings budget").sessionId(42L)
                    .useRag(false).useWebSearch(false).build();
        }
        @Override public void close() { ReflectionTestUtils.invokeMethod(registry, "shutdown"); }
    }
}
