package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatApiControllerStateSecurityTest {

    @Test
    void originalRequestReceiptCanRecoverOwnedCapabilityWithoutBroadStateTokenEcho() {
        var history = mock(ChatHistoryService.class);
        var owners = mock(ClientOwnerKeyResolver.class);
        var registry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        var controller = controller(history, registry, owners);
        var session = new ChatSession("synthetic", "owner-key", "ANON");
        session.setId(9L);
        when(owners.ownerKey()).thenReturn("owner-key");
        when(history.getSessionWithMessages(9L)).thenReturn(session);
        var run = registry.beginOrJoin(9L).context();
        String requestKey = "synthetic-original-request";
        assertTrue(java.util.Arrays.stream(ChatRunRegistry.class.getMethods())
                .anyMatch(method -> method.getName().equals("bindRequestReceipt")), "exact receipt binding is required");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry, "bindRequestReceipt", run,
                com.example.lms.service.AttachmentOwnerIdentity.forAnonymous("owner-key").hash(),
                org.apache.commons.codec.digest.DigestUtils.sha256Hex(requestKey));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("Idempotency-Key", requestKey);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            assertEquals(run.clientToken(), controller.state(9L, false, null, null).getBody().get("runToken"));
            request.removeHeader("Idempotency-Key");
            assertFalse(controller.state(9L, false, null, null).getBody().containsKey("runToken"));
            request.addHeader("Idempotency-Key", "unknown-receipt");
            var unknown = controller.state(9L, false, null, null).getBody();
            assertFalse(unknown.containsKey("runToken"));
            assertEquals(false, unknown.get("terminal"), "receipt miss cannot certify old-run termination");
            request.removeHeader("Idempotency-Key");
            request.addHeader("Idempotency-Key", requestKey);
            when(owners.ownerKey()).thenReturn("foreign-key");
            assertFalse(controller.state(9L, false, null, null).getBody().containsKey("runToken"));
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry, "shutdown");
        }
    }

    @Test
    void stateNeutralizesForeignGuestSessionEvenWhenCurrentRunExists() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(historyService, runRegistry, ownerKeyResolver);
        ChatSession session = new ChatSession("foreign", "owner-key", "ANON");
        session.setId(7L);

        when(ownerKeyResolver.ownerKey()).thenReturn("other-key");
        when(historyService.getSessionWithMessages(7L)).thenReturn(session);
        when(runRegistry.isRunning(7L)).thenReturn(true);

        var response = controller.state(7L, true, "foreign-token", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("running"));
        assertEquals("missing_or_replaced", response.getBody().get("runStatus"));
        assertEquals(false, response.getBody().get("attachable"));
        assertNull(response.getBody().get("traceHtml"));
    }

    @Test
    void restoreProbeSoftensMissingAndForeignSessionsWithoutCreatingAnEnumerationOracle() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, mock(ChatRunRegistry.class), ownerKeyResolver);
        ChatSession foreignSession = new ChatSession("foreign", "owner-key", "ANON");
        foreignSession.setId(7L);

        when(ownerKeyResolver.ownerKey()).thenReturn("other-key");
        when(historyService.getSessionWithMessages(7L)).thenReturn(foreignSession);
        when(historyService.getSessionWithMessages(42L)).thenReturn(null);

        var missing = controller.getSession(42L, true, null);
        var foreign = controller.getSession(7L, true, null);
        Map<String, Object> expected = Map.of(
                "found", false,
                "action", "RESET_SESSION",
                "error", "SESSION_UNAVAILABLE");

        assertEquals(HttpStatus.OK, missing.getStatusCode());
        assertEquals(HttpStatus.OK, foreign.getStatusCode());
        assertEquals(expected, missing.getBody());
        assertEquals(expected, foreign.getBody());
    }

    @Test
    void ordinarySessionDetailFailuresRemainStrict() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, mock(ChatRunRegistry.class), ownerKeyResolver);
        ChatSession foreignSession = new ChatSession("foreign", "owner-key", "ANON");
        foreignSession.setId(7L);

        when(ownerKeyResolver.ownerKey()).thenReturn("other-key");
        when(historyService.getSessionWithMessages(7L)).thenReturn(foreignSession);
        when(historyService.getSessionWithMessages(42L)).thenReturn(null);

        var missing = controller.getSession(42L, false, null);
        var foreign = controller.getSession(7L, false, null);

        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("SESSION_NOT_FOUND", ((Map<?, ?>) missing.getBody()).get("error"));
        assertEquals(HttpStatus.FORBIDDEN, foreign.getStatusCode());
        assertNull(foreign.getBody());
    }

    @Test
    void authorizedStateDescribesOnlyThePresentedExactRunWithoutEchoingItsToken() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = controller(historyService, runRegistry, ownerKeyResolver);
        ChatSession session = new ChatSession("owned", "owner-key", "ANON");
        session.setId(8L);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-key");
        when(historyService.getSessionWithMessages(8L)).thenReturn(session);
        ChatRunRegistry.BeginResult first = runRegistry.beginOrJoin(8L);
        String token = first.context().clientToken();

        Map<String, Object> running = controller.state(8L, false, token, null).getBody();
        assertEquals("running", running.get("runStatus"));
        assertEquals(true, running.get("attachable"));
        assertEquals(false, running.get("terminal"));
        assertFalse(running.containsKey("runToken"));

        runRegistry.markDone(first.context());
        ChatRunRegistry.BeginResult replacement = runRegistry.beginOrJoin(8L);
        Map<String, Object> terminal = controller.state(8L, false, token, null).getBody();
        assertEquals("done", terminal.get("runStatus"));
        assertEquals(false, terminal.get("running"),
                "R2 activity must not leak into the exact R1 state projection");
        assertEquals(true, terminal.get("attachable"));
        assertEquals(true, terminal.get("terminal"));
        assertEquals(false, terminal.get("currentRun"));

        Map<String, Object> stale = controller.state(8L, false, "unknown-token", null).getBody();
        assertEquals("missing_or_replaced", stale.get("runStatus"));
        assertEquals(false, stale.get("running"));
        assertEquals(false, stale.get("attachable"));
        assertTrue(runRegistry.cancelExact(8L, replacement.context().clientToken()));
    }

    @Test
    void persistedTerminalOutcomeDisablesUiReplayButKeepsExactReplayAddressable() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = controller(historyService, runRegistry, ownerKeyResolver);
        ChatSession session = new ChatSession("owned", "owner-key", "ANON");
        session.setId(9L);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-key");
        when(historyService.getSessionWithMessages(9L)).thenReturn(session);
        when(historyService.getLastAssistantMessage(9L)).thenReturn(java.util.Optional.of("durable answer"));

        ChatRunRegistry.BeginResult run = runRegistry.beginOrJoin(9L);
        String token = run.context().clientToken();
        assertTrue(run.context().markGenerationSucceeded());
        assertTrue(run.context().tryBeginTranscriptCommit());
        assertTrue(run.context().markPersisted());
        assertTrue(run.context().claimTerminalEvent("delivery_pending"));
        assertTrue(run.context().recordFinalEmit("OK", false));
        assertTrue(runRegistry.markDone(run.context()));

        Map<String, Object> terminal = controller.state(9L, false, token, null).getBody();
        assertEquals(false, terminal.get("attachable"));
        assertEquals(true, terminal.get("persisted"));
        assertEquals(1, terminal.get("persistenceCount"));
        assertEquals("durable answer", terminal.get("lastAssistant"));
        assertTrue(runRegistry.attachExact(9L, token).isPresent(),
                "the UI state gate must not destroy exact diagnostic replay");

        ChatRunRegistry.BeginResult replacement = runRegistry.beginOrJoin(9L);
        Map<String, Object> stale = controller.state(9L, false, token, null).getBody();
        assertEquals(false, stale.get("currentRun"));
        assertNull(stale.get("lastAssistant"),
                "an old token must not receive the replacement session's last assistant");
        assertTrue(runRegistry.cancelExact(9L, replacement.context().clientToken()));
    }

    private static ChatApiController controller(
            ChatHistoryService historyService,
            ChatRunRegistry runRegistry,
            ClientOwnerKeyResolver ownerKeyResolver) {
        return new ChatApiController(
                historyService,
                mock(ChatService.class),
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
                ownerKeyResolver);
    }
}
