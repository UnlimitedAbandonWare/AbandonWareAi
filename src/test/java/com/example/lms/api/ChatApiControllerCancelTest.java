package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.service.ChatService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.chat.ChatRunRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatApiControllerCancelTest {

    @Test
    void deleteCancelsSessionFenceBeforeRemovingHistory() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(historyService, mock(ChatService.class), runRegistry);
        ChatSession session = new ChatSession("active", "owner-a", "ANON");
        session.setId(42L);
        when(historyService.getSessionWithMessages(42L)).thenReturn(session);
        TestingAuthenticationToken administrator =
                new TestingAuthenticationToken("admin", "unused", "ROLE_ADMIN");

        controller.deleteSession(42L, administrator);

        org.mockito.InOrder ordered = inOrder(runRegistry, historyService);
        ordered.verify(runRegistry).cancelSessionForDeletion(42L);
        ordered.verify(historyService).deleteSession(42L);
    }

    @Test
    void streamTranscriptAndTraceWritesStayInsideTerminalSideEffectGate() throws IOException {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/api/ChatApiController.java"));
        int durableStart = source.indexOf("Runnable durablePersistence = () -> {");
        int durableEnd = source.indexOf("boolean durablePersistenceAccepted;", durableStart);
        String durableBlock = source.substring(durableStart, durableEnd);
        int gatedInvocation = source.indexOf(
                "committingRun.runTerminalSideEffect(durablePersistence);", durableEnd);

        org.junit.jupiter.api.Assertions.assertTrue(durableStart > 0 && durableEnd > durableStart);
        org.junit.jupiter.api.Assertions.assertTrue(durableBlock.contains("appendMessageReturningId("));
        org.junit.jupiter.api.Assertions.assertTrue(durableBlock.contains("committingRun.markPersisted()"));
        org.junit.jupiter.api.Assertions.assertTrue(durableBlock.contains("historyService.appendMessage("));
        org.junit.jupiter.api.Assertions.assertTrue(
                durableBlock.contains("ChatTraceSnapshotPointerPersister.persist("));
        org.junit.jupiter.api.Assertions.assertTrue(
                durableBlock.contains("historyService.updateSessionAnswerModeAndTrace("));
        org.junit.jupiter.api.Assertions.assertTrue(gatedInvocation > durableEnd,
                "the complete durable block must be admitted through the exact run gate");
    }

    @Test
    void deleteSessionStopsItsActiveRunBeforeHistoryRemovalReturns() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = controller(historyService, mock(ChatService.class), runRegistry);
        ChatSession session = new ChatSession("active", "owner-a", "ANON");
        session.setId(42L);
        when(historyService.getSessionWithMessages(42L)).thenReturn(session);
        runRegistry.beginOrJoin(42L);
        TestingAuthenticationToken administrator =
                new TestingAuthenticationToken("admin", "unused", "ROLE_ADMIN");

        try {
            controller.deleteSession(42L, administrator);

            verify(historyService).deleteSession(42L);
            assertFalse(runRegistry.isRunning(42L),
                    "history deletion must return only after the active run is fenced");
        } finally {
            ReflectionTestUtils.invokeMethod(runRegistry, "shutdown");
        }
    }

    @Test
    void deleteReturnsRetriableFailureWithoutRemovingHistoryWhenFenceTimesOut() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(historyService, mock(ChatService.class), runRegistry);
        ChatSession session = new ChatSession("active", "owner-a", "ANON");
        session.setId(42L);
        when(historyService.getSessionWithMessages(42L)).thenReturn(session);
        org.mockito.Mockito.doThrow(ChatRunRegistry.SessionDeletionFenceException.waitTimedOut())
                .when(runRegistry).cancelSessionForDeletion(42L);
        TestingAuthenticationToken administrator =
                new TestingAuthenticationToken("admin", "unused", "ROLE_ADMIN");

        var response = controller.deleteSession(42L, administrator);

        org.junit.jupiter.api.Assertions.assertEquals(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                response.getStatusCode());
        org.junit.jupiter.api.Assertions.assertEquals(
                Map.of(
                        "action", "RETRY",
                        "error", "SESSION_DELETION_FENCE_UNAVAILABLE",
                        "reason", ChatRunRegistry.SESSION_DELETION_CANCEL_WAIT_TIMED_OUT),
                response.getBody());
        verify(historyService, never()).deleteSession(42L);
    }

    @Test
    void cancelAcceptsSessionIdFromJsonBody() {
        ChatService chatService = mock(ChatService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        stubSuccessfulCancel(runRegistry, 42L, "run-42");
        ChatApiController controller = controller(chatService, runRegistry);

        controller.cancel(null, Map.of("sessionId", 42, "runToken", "run-42"), null, null);

        verify(runRegistry).cancelExact(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("run-42"), org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(chatService, never()).cancelSession(42L);
    }

    @Test
    void cancelQueryParamWinsOverJsonBody() {
        ChatService chatService = mock(ChatService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        stubSuccessfulCancel(runRegistry, 7L, "header-run");
        ChatApiController controller = controller(chatService, runRegistry);

        controller.cancel(7L, Map.of("sessionId", 42, "runToken", "body-run"), "header-run", null);

        verify(runRegistry).cancelExact(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("header-run"), org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(chatService, never()).cancelSession(7L);
    }

    @Test
    void cancelPersistsStoppedAssistantMarkerForRunningSession() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        stubSuccessfulCancel(runRegistry, 42L, "run-42");
        ChatApiController controller = controller(historyService, chatService, runRegistry);

        controller.cancel(null, Map.of("sessionId", 42), "run-42", null);

        verify(chatService, never()).cancelSession(42L);
        verify(historyService).appendMessage(42L, "assistant", "Response stopped");
        verify(runRegistry).cancelExact(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("run-42"), org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void cancelUsesAtomicRegistryTransitionWithoutWritingLegacySessionFlag() {
        ChatService chatService = mock(ChatService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        stubSuccessfulCancel(runRegistry, 42L, "run-42");
        ChatApiController controller = controller(chatService, runRegistry);

        controller.cancel(null, Map.of("sessionId", 42), "run-42", null);

        verify(runRegistry).cancelExact(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("run-42"), org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(chatService, never()).cancelSession(42L);
    }

    @Test
    void cancelDoesNotPoisonNextTurnWhenSessionHasNoRunningStream() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        when(runRegistry.cancelExact(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("stale-run"), org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenReturn(false);
        ChatApiController controller = controller(historyService, chatService, runRegistry);

        var response = controller.cancel(null, Map.of("sessionId", 42), "stale-run", null);

        verify(chatService, never()).cancelSession(42L);
        verify(historyService, never()).appendMessage(42L, "assistant", "Response stopped");
        verify(runRegistry).cancelExact(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("stale-run"), org.mockito.ArgumentMatchers.any(Runnable.class));
        org.junit.jupiter.api.Assertions.assertEquals(false, response.getBody().get("cancelled"));
    }

    @Test
    void cancelWithoutRunTokenNeverFallsBackToSessionOnlyMutation() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(historyService, chatService, runRegistry);

        var response = controller.cancel(null, Map.of("sessionId", 42), null, null);

        verify(runRegistry, never()).cancelExact(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(historyService, never()).appendMessage(42L, "assistant", "Response stopped");
        org.junit.jupiter.api.Assertions.assertEquals(
                "run_not_found_or_not_cancellable", response.getBody().get("reason"));
    }

    @Test
    void tokenlessCancelDoesNotRevealWhetherSessionExistsOrLoadHistory() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(historyService, chatService, runRegistry);
        ChatSession foreign = new ChatSession("foreign", "owner-a", "ANON");
        foreign.setId(43L);
        when(historyService.getSessionWithMessages(43L)).thenReturn(foreign);
        when(historyService.getSessionWithMessages(44L)).thenReturn(null);

        var foreignResponse = controller.cancel(null, Map.of("sessionId", 43), null, null);
        var missingResponse = controller.cancel(null, Map.of("sessionId", 44), null, null);

        org.junit.jupiter.api.Assertions.assertEquals(foreignResponse.getBody(), missingResponse.getBody());
        org.junit.jupiter.api.Assertions.assertEquals(
                "run_not_found_or_not_cancellable", foreignResponse.getBody().get("reason"));
        verify(historyService, never()).getSessionWithMessages(org.mockito.ArgumentMatchers.anyLong());
        verify(runRegistry, never()).cancelExact(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    private static ChatApiController controller(ChatService chatService, ChatRunRegistry runRegistry) {
        return controller(mock(ChatHistoryService.class), chatService, runRegistry);
    }

    private static void stubSuccessfulCancel(ChatRunRegistry registry, Long sessionId, String runToken) {
        when(registry.cancelExact(org.mockito.ArgumentMatchers.eq(sessionId),
                org.mockito.ArgumentMatchers.eq(runToken), org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(2).run();
                    return true;
                });
    }

    private static ChatApiController controller(
            ChatHistoryService historyService,
            ChatService chatService,
            ChatRunRegistry runRegistry) {
        return new ChatApiController(
                historyService,
                chatService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                runRegistry,
                null);
    }
}
