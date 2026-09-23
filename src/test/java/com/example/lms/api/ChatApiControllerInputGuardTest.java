package com.example.lms.api;

import com.example.lms.dto.ChatResponseDto;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.domain.ChatSession;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.orchestration.control.RagControlPresentationBoundary;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatStreamEmitter;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatApiControllerInputGuardTest {

    @Test
    void legacyUiAcknowledgesRenderedFinalAndRestoresPersistedRunWithoutReplayBubble() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("main/resources/static/js/chat.js"),
                java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(source.contains("async function acknowledgeExactRun(sessionId, runToken, phase = \"ready\")"));
        assertTrue(source.contains("JSON.stringify({ sessionId, runToken: token, phase })"));
        int acknowledgement = source.indexOf(
                "async function acknowledgeExactRun(sessionId, runToken, phase = \"ready\")");
        int acknowledgementEnd = source.indexOf(
                "async function requestServerCancelWithTimeout", acknowledgement);
        String acknowledgementBody = source.substring(acknowledgement, acknowledgementEnd);
        assertTrue(source.contains("const FINAL_ACK_TIMEOUT_MS ="));
        assertTrue(source.contains("const READY_ACK_TIMEOUT_MS ="));
        assertTrue(acknowledgementBody.contains("Promise.race(["),
                "a stalled final ACK must not hold the composer busy indefinitely");
        assertTrue(acknowledgementBody.contains("resolve(false)"),
                "ACK timeout must retain the exact capability for reload recovery");
        assertTrue(acknowledgementBody.contains("new AbortController()"));
        assertTrue(acknowledgementBody.contains("signal: acknowledgementAbort.signal"));
        assertTrue(acknowledgementBody.contains("acknowledgementAbort.abort()"),
                "timeout must also release the underlying request and its run capability");
        assertTrue(acknowledgementBody.contains("phase === \"ready\""),
                "the initial ready handshake needs a longer bound than terminal delivery ACKs");
        int resume = source.indexOf("async function resumeStoredRunIfNeeded");
        int persistedRecovery = source.indexOf("if (runState?.persisted === true)", resume);
        int replayGate = source.indexOf("runState?.attachable !== true", persistedRecovery);
        assertTrue(resume >= 0 && persistedRecovery > resume && replayGate > persistedRecovery,
                "persisted transcript recovery must win before terminal replay creates a bubble");

        int parser = source.indexOf("const parser = createSseEventParser");
        int snapshot = source.indexOf("const exactFinalRun = effectiveType === \"final\"", parser);
        int render = source.indexOf("renderChatEvent(eventPayload, assistant, effectiveType)", snapshot);
        int finalAck = source.indexOf("exactFinalRun.sessionId, exactFinalRun.runToken, \"final\"", render);
        int awaitAck = source.indexOf("if (finalAckPromise) await finalAckPromise", finalAck);
        assertTrue(parser >= 0 && snapshot > parser && render > snapshot && finalAck > render && awaitAck > finalAck,
                "final ACK must use the exact pre-render capability after the final is rendered");

        int finalBranch = source.indexOf("} else if (type === \"final\")");
        int nextBranch = source.indexOf("} else if (type ===", finalBranch + 1);
        assertTrue(finalBranch >= 0 && nextBranch > finalBranch);
        assertFalse(source.substring(finalBranch, nextBranch).contains("clearActiveRunIdentity()"),
                "failed final ACK must retain the exact capability for reload recovery");
    }

    @Test
    void chatSyncRejectsNullPayloadBeforeCallingServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);

        var response = controller.chatSync(null, null, new MockHttpServletRequest());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ChatResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("bad_request", body.getContent());
        verifyNoInteractions(historyService, chatService, settingsService, ownerKeyResolver);
    }

    @Test
    void chatRejectsNullPayloadBeforeCallingServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);

        var response = controller.chat(null, null, new MockHttpServletRequest()).block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ChatResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("bad_request", body.getContent());
        verifyNoInteractions(historyService, chatService, settingsService, ownerKeyResolver);
    }

    @Test
    void publicChatPreservesOmittedRagIntentUntilSettingsMerge() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ChatSession session = new ChatSession("public chat intent", "owner-a", "ANON");
        session.setId(210L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(historyService.appendMessageReturningId(210L, "assistant", "Public chat answer."))
                .thenReturn(2101L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("Public chat answer.", "mock-model", false));

        var response = controller.chat(
                        ChatRequestDto.builder()
                                .message("public chat intent")
                                .useWebSearch(false)
                                .build(),
                        null,
                        new MockHttpServletRequest())
                .block(Duration.ofSeconds(5));

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<ChatRequestDto> dtoCaptor = ArgumentCaptor.forClass(ChatRequestDto.class);
        verify(chatService).continueChat(dtoCaptor.capture(), any());
        ChatRequestDto.RetrievalRequestIntent intent = dtoCaptor.getValue().getRetrievalRequestIntent();
        assertNotNull(intent);
        assertEquals(Boolean.FALSE, intent.webSearch());
        assertNull(intent.rag());
    }

    @Test
    void attachmentOwnerIdentityIsNeitherAcceptedFromNorWrittenToJson() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String attackerHash = "a".repeat(64);

        ChatRequestDto parsed = mapper.readValue(
                "{\"message\":\"hello\",\"attachmentIds\":[\"att-1\"],"
                        + "\"attachmentOwnerIdentity\":{\"hash\":\"" + attackerHash + "\"}}",
                ChatRequestDto.class);

        assertNull(parsed.getAttachmentOwnerIdentity());

        AttachmentOwnerIdentity serverOwner = AttachmentOwnerIdentity.forAnonymous("owner-a");
        parsed.bindAttachmentOwnerIdentity(serverOwner);
        String serialized = mapper.writeValueAsString(parsed);

        assertFalse(serialized.contains("attachmentOwnerIdentity"));
        assertFalse(serialized.contains(serverOwner.hash()));
    }

    @Test
    void syncMetadataExtractionCarriesBoundOwnerAndNeverUsesOwnerlessOverload() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ReflectionTestUtils.setField(controller, "attachmentService", attachmentService);
        ChatSession session = new ChatSession("attachment owner path", "owner-a", "ANON");
        session.setId(212L);
        AttachmentOwnerIdentity owner = AttachmentOwnerIdentity.forAnonymous("owner-a");

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(historyService.appendMessageReturningId(212L, "assistant", "Attachment-safe answer."))
                .thenReturn(2121L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("Attachment-safe answer.", "mock-model", false));
        when(attachmentService.asDocumentsForSession(List.of("att-1"), "212", owner))
                .thenReturn(List.of());

        ResponseEntity<ChatResponseDto> response = controller.chat(
                        ChatRequestDto.builder()
                                .message("use my attachment")
                                .attachmentIds(List.of("att-1"))
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        null,
                        new MockHttpServletRequest())
                .block(Duration.ofSeconds(5));

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<ChatRequestDto> requestCaptor = ArgumentCaptor.forClass(ChatRequestDto.class);
        verify(chatService).continueChat(requestCaptor.capture(), any());
        assertEquals(owner, requestCaptor.getValue().getAttachmentOwnerIdentity());
        verify(attachmentService).asDocumentsForSession(List.of("att-1"), "212", owner);
        verify(attachmentService, never()).asDocumentsForSession(List.of("att-1"), "212");
    }

    @Test
    void publicChatCarriesSameAbsoluteTimeBudgetIntoBoundedElasticWorker() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ChatSession session = new ChatSession("worker budget propagation", "owner-a", "ANON");
        session.setId(211L);
        java.util.concurrent.atomic.AtomicReference<com.abandonware.ai.addons.budget.TimeBudget> observedBudget =
                new java.util.concurrent.atomic.AtomicReference<>();

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(historyService.appendMessageReturningId(211L, "assistant", "Budget-aware answer."))
                .thenReturn(2111L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenAnswer(invocation -> {
                    observedBudget.set(com.abandonware.ai.addons.budget.TimeBudgetContext.get());
                    return ChatResult.of("Budget-aware answer.", "mock-model", false);
                });

        com.abandonware.ai.addons.budget.TimeBudget requestBudget =
                new com.abandonware.ai.addons.budget.TimeBudget(30_000L);
        reactor.core.publisher.Mono<ResponseEntity<ChatResponseDto>> responseMono;
        com.abandonware.ai.addons.budget.TimeBudgetContext.set(requestBudget);
        try {
            responseMono = controller.chat(
                    ChatRequestDto.builder()
                            .message("worker budget propagation")
                            .useRag(false)
                            .useWebSearch(false)
                            .build(),
                    null,
                    new MockHttpServletRequest());
        } finally {
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
        }

        ResponseEntity<ChatResponseDto> response = responseMono.block(Duration.ofSeconds(5));

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(requestBudget, observedBudget.get(),
                "the async worker must consume the request's original absolute deadline");
        assertNull(com.abandonware.ai.addons.budget.TimeBudgetContext.get(),
                "request budget must remain cleared on the caller after subscription");
    }

    @Test
    void chatStreamRejectsNullPayloadWithSingleErrorEventBeforeCallingServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);

        var events = controller.chatStream(null, false, false, null, new MockHttpServletRequest())
                .collectList()
                .block();

        assertNotNull(events);
        assertEquals(1, events.size());
        ChatStreamEvent body = events.get(0).data();
        assertNotNull(body);
        assertEquals("error", body.type());
        assertEquals("bad_request", body.data());
        verifyNoInteractions(historyService, chatService, settingsService, ownerKeyResolver);
    }

    @Test
    void chatStreamCarriesRawBodyByteCountIntoWorkerBudgetTrace() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        PublicRequestBudgetGuard guard = spy(new PublicRequestBudgetGuard());
        List<String> observed = new CopyOnWriteArrayList<>();
        AtomicInteger effectiveCalls = new AtomicInteger();
        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-budget-trace");
        org.mockito.Mockito.doAnswer(invocation -> {
            observed.add(String.valueOf(com.example.lms.search.TraceStore.get(
                    "public.request.budget.bodyBytes")));
            if (effectiveCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("stop after worker budget capture");
            }
            return null;
        }).when(guard).validateChatEffective(any(ChatRequestDto.class));
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "publicRequestBudgetGuard", guard);
        com.example.lms.search.TraceStore.put("public.request.budget.bodyBytes", 123L);

        try {
            controller.chatStream(
                            ChatRequestDto.builder().message("trace carry").build(),
                            false, false, null, new MockHttpServletRequest())
                    .collectList()
                    .block(Duration.ofSeconds(3));
            assertEquals(List.of("123", "123"), observed);
        } finally {
            com.example.lms.search.TraceStore.clear();
        }
    }

    @Test
    void chatRejectsForeignGuestSessionBeforeCallingChatServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ChatSession foreign = new ChatSession("foreign", "owner-a", "ANON");
        foreign.setId(7L);

        when(ownerKeyResolver.ownerKey()).thenReturn("owner-b");
        when(historyService.getSessionWithMessages(7L, 1)).thenReturn(foreign);

        var response = controller.chat(
                        ChatRequestDto.builder().message("hello").sessionId(7L).build(),
                        null,
                        new MockHttpServletRequest())
                .block();

        assertNotNull(response);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        ChatResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("session_forbidden", body.getContent());
        verifyNoInteractions(chatService);
    }

    @Test
    void chatSyncRejectsForeignGuestSessionBeforeCallingChatServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ChatSession foreign = new ChatSession("foreign", "owner-a", "ANON");
        foreign.setId(8L);

        when(ownerKeyResolver.ownerKey()).thenReturn("owner-b");
        when(historyService.getSessionWithMessages(8L, 1)).thenReturn(foreign);

        var response = controller.chatSync(
                ChatRequestDto.builder().message("hello").sessionId(8L).build(),
                null, new MockHttpServletRequest());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("session_forbidden", response.getBody().getContent());
        verifyNoInteractions(chatService);
    }

    @Test
    void chatStreamRejectsForeignGuestSessionBeforeCallingChatServices() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ChatSession foreign = new ChatSession("foreign", "owner-a", "ANON");
        foreign.setId(9L);

        when(ownerKeyResolver.ownerKey()).thenReturn("owner-b");
        when(historyService.getSessionWithMessages(9L, 1)).thenReturn(foreign);

        var events = controller.chatStream(
                        ChatRequestDto.builder().message("hello").sessionId(9L).build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block();

        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("error", events.get(0).data().type());
        assertEquals("session_forbidden", events.get(0).data().data());
        verifyNoInteractions(chatService);
    }

    @Test
    void chatStreamEmitsNonBlankTokenAndFinalEventWhenChatServiceReturnsAnswer() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry);
        ChatSession session = new ChatSession("Harmony SSE smoke", "owner-a", "ANON");
        session.setId(12L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 12L);
        when(historyService.appendMessageReturningId(12L, "assistant", "Harmony answer is ready."))
                .thenReturn(55L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("Harmony answer is ready.", "mock-model", false));

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("Harmony SSE smoke")
                                .useWebSearch(false)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e ->
                e.data() != null
                        && "token".equals(e.data().type())
                        && e.data().data() != null
                        && !e.data().data().isBlank()));
        assertTrue(events.stream().anyMatch(e ->
                e.data() != null
                        && "final".equals(e.data().type())));
        assertTrue(events.stream().anyMatch(e ->
                e.data() != null
                        && "final".equals(e.data().type())
                        && "Harmony answer is ready.".equals(e.data().data())));
        verify(historyService, never()).appendMessage(12L, "user", "Harmony SSE smoke");
        ArgumentCaptor<ChatRequestDto> dtoCaptor = ArgumentCaptor.forClass(ChatRequestDto.class);
        verify(chatService).continueChat(dtoCaptor.capture(), any());
        ChatRequestDto.RetrievalRequestIntent intent = dtoCaptor.getValue().getRetrievalRequestIntent();
        assertNotNull(intent);
        assertEquals(Boolean.FALSE, intent.webSearch());
        assertNull(intent.rag());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void nullAssistantWriteNeverClaimsPersistedOrRestoresAnotherTurn(boolean failFinalEmit) {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        ReflectionTestUtils.setField(runRegistry, "replayCapacity", 32);
        ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        try {
            ChatApiController controller = spy(controller(
                    historyService, chatService, settingsService, ownerKeyResolver, runRegistry));
            ChatSession session = new ChatSession("nullable write fixture", "owner-a", "ANON");
            session.setId(16L);
            when(settingsService.getAllSettings()).thenReturn(Map.of());
            when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
            when(historyService.startNewSession(any(), any(), any(), any(), any()))
                    .thenReturn(Optional.of(session));
            when(historyService.getSessionWithMessages(16L)).thenReturn(session);
            when(historyService.getLastAssistantMessage(16L))
                    .thenReturn(Optional.of("An older unrelated answer."));
            when(historyService.appendMessageReturningId(16L, "assistant", "Current answer."))
                    .thenReturn(null);
            when(chatService.continueChat(any(ChatRequestDto.class), any()))
                    .thenReturn(ChatResult.of("Current answer.", "mock-model", false));
            if (failFinalEmit) {
                org.mockito.Mockito.doReturn(Sinks.EmitResult.FAIL_TERMINATED)
                        .when(controller).emitStreamEvent(any(), any());
            }
            var events = controller.chatStream(
                            ChatRequestDto.builder().message("nullable write fixture")
                                    .useRag(false).useWebSearch(false).build(),
                            false, false, null, new MockHttpServletRequest())
                    .collectList().block(Duration.ofSeconds(5));
            assertNotNull(events);
            assertEquals(!failFinalEmit,
                    events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())),
                    "a nullable transcript write must preserve fail-soft final delivery");
            verify(historyService, times(1))
                    .appendMessageReturningId(16L, "assistant", "Current answer.");
            String token = runRegistry.currentRunToken(16L).orElseThrow();
            Map<String, Object> state = controller.state(16L, false, token, null).getBody();
            assertNotNull(state);
            assertEquals(true, state.get("generationSucceeded"));
            assertEquals(false, state.get("persisted"));
            assertEquals(0, state.get("persistenceCount"));
            assertNull(state.get("lastAssistant"), "an exact run must not restore an older turn");
            assertEquals(true, state.get("attachable"));
            for (String phase : java.util.List.of("final", "recovery")) {
                var ack = controller.acknowledgeRun(null,
                        Map.of("sessionId", 16L, "runToken", token, "phase", phase), null, null);
                assertNotNull(ack.getBody());
                assertEquals(false, ack.getBody().get("acknowledged"),
                        "no transcript exists for either acknowledgement phase");
            }
        } finally {
            ReflectionTestUtils.invokeMethod(runRegistry, "shutdown");
        }
    }

    @Test
    void successfulGenerationWithFinalEmitFailurePersistsOnceAndRestoresFromTranscript() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 32);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = spy(controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry));
        ChatSession session = new ChatSession("delivery fault fixture", "owner-a", "ANON");
        session.setId(15L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(historyService.getSessionWithMessages(15L)).thenReturn(session);
        when(historyService.getLastAssistantMessage(15L)).thenReturn(Optional.of("Persisted answer."));
        when(historyService.appendMessageReturningId(15L, "assistant", "Persisted answer."))
                .thenReturn(57L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("Persisted answer.", "mock-model", false));
        org.mockito.Mockito.doReturn(Sinks.EmitResult.FAIL_TERMINATED)
                .when(controller).emitStreamEvent(any(), any());

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("delivery fault fixture")
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().noneMatch(e -> e.data() != null && "final".equals(e.data().type())));
        verify(historyService, times(1))
                .appendMessageReturningId(15L, "assistant", "Persisted answer.");
        String token = runRegistry.currentRunToken(15L).orElseThrow();
        Map<String, Object> state = controller.state(15L, false, token, null).getBody();
        assertNotNull(state);
        assertEquals(true, state.get("generationSucceeded"));
        assertEquals(true, state.get("persisted"));
        assertEquals(1, state.get("persistenceCount"));
        assertEquals("fail_terminated", state.get("finalEmitResult"));
        assertEquals(false, state.get("finalDeliveryAccepted"));
        assertEquals("final_emit_failed", state.get("finalDeliveryFailureReason"));
        assertEquals(0, state.get("terminalEventCount"));
        assertEquals(false, state.get("attachable"));
        assertEquals("Persisted answer.", state.get("lastAssistant"));

        ResponseEntity<Map<String, Object>> rejectedFinalAck = controller.acknowledgeRun(
                null,
                Map.of("sessionId", 15L, "runToken", token, "phase", "final"),
                null,
                null);
        assertEquals(false, rejectedFinalAck.getBody().get("acknowledged"),
                "a failed local final emit must never be promoted to delivered by a final ACK");

        ResponseEntity<Map<String, Object>> recoveredAck = controller.acknowledgeRun(
                null,
                Map.of("sessionId", 15L, "runToken", token, "phase", "recovery"),
                null,
                null);
        assertEquals(true, recoveredAck.getBody().get("acknowledged"));
        assertEquals("recovery_acknowledged", recoveredAck.getBody().get("reason"));
        Map<String, Object> recoveredState = controller.state(15L, false, token, null).getBody();
        assertNotNull(recoveredState);
        assertEquals("fail_terminated", recoveredState.get("finalEmitResult"),
                "transcript recovery must preserve the original transport failure");
        assertEquals(true, recoveredState.get("finalDeliveryAccepted"));
        assertEquals("recovered", recoveredState.get("terminalReason"));
    }

    @Test
    void chatStreamBlankOfficialEvidenceAnswerFallsBackToEvidenceNeeded() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry);
        RagControlPresentationBoundary boundary = mock(RagControlPresentationBoundary.class);
        when(boundary.projectResult(any(), eq(true))).thenAnswer(invocation ->
                RagControlPresentationBoundary.Projection.passThrough(invocation.getArgument(0)));
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "ragControlPresentationBoundary", boundary);
        String query = "RAG web-search verification: answer only from official OpenAI and Supabase "
                + "docs/changelog evidence; if official evidence is missing say evidence_needed.";
        ChatSession session = new ChatSession(query, "owner-a", "ANON");
        session.setId(14L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 14L);
        when(historyService.appendMessageReturningId(
                org.mockito.ArgumentMatchers.eq(14L),
                org.mockito.ArgumentMatchers.eq("assistant"),
                any()))
                .thenReturn(56L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("   ", "mock-model", false));

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message(query)
                                .useRag(true)
                                .useWebSearch(false)
                                .searchMode(SearchMode.FORCE_DEEP)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e ->
                e.data() != null
                        && "token".equals(e.data().type())
                        && String.valueOf(e.data().data()).contains("evidence_needed")));
        ArgumentCaptor<String> answerCaptor = ArgumentCaptor.forClass(String.class);
        verify(historyService).appendMessageReturningId(
                org.mockito.ArgumentMatchers.eq(14L),
                org.mockito.ArgumentMatchers.eq("assistant"),
                answerCaptor.capture());
        assertTrue(answerCaptor.getValue().contains("evidence_needed"));
    }

    @Test
    void chatStreamDoesNotPersistFinalAssistantAnswerAfterRunCancelled() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry);
        ChatSession session = new ChatSession("cancelled late answer", "owner-a", "ANON");
        session.setId(13L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        ChatRunExecutionContext cancelledRun = mock(ChatRunExecutionContext.class);
        when(cancelledRun.isCancellationRequested()).thenReturn(false, true);
        when(runRegistry.beginOrJoin(13L))
                .thenReturn(new ChatRunRegistry.BeginResult(cancelledRun, true));
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("Late answer after cancel.", "mock-model", false));

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("cancelled late answer")
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().noneMatch(e ->
                e.data() != null
                        && "final".equals(e.data().type())
                        && "Late answer after cancel.".equals(e.data().data())));
        verify(historyService, never()).appendMessageReturningId(13L, "assistant", "Late answer after cancel.");
    }

    @Test
    void cancellationDuringTokenDeliveryStopsBeforeTranscriptCommitAndEmitsNoLateFinal() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 32);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = spy(controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry));
        ChatSession session = new ChatSession("cancel during tokens", "owner-a", "ANON");
        session.setId(16L);
        String answer = "A".repeat(180);
        AtomicInteger tokenEmits = new AtomicInteger();

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of(answer, "mock-model", false));
        org.mockito.Mockito.doAnswer(invocation -> {
            Sinks.EmitResult emitted = (Sinks.EmitResult) invocation.callRealMethod();
            if (tokenEmits.incrementAndGet() == 1) {
                assertTrue(runRegistry.cancelExact(
                        16L, runRegistry.currentRunToken(16L).orElseThrow()));
            }
            return emitted;
        }).when(controller).emitTokenStreamEvent(any(), any());

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("cancel during tokens")
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        false, false, null, new MockHttpServletRequest())
                .collectList().block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertEquals(1L, events.stream()
                .filter(e -> e.data() != null && "token".equals(e.data().type()))
                .count());
        assertTrue(events.stream().noneMatch(e -> e.data() != null && "final".equals(e.data().type())));
        verify(historyService, never()).appendMessageReturningId(16L, "assistant", answer);
    }

    @Test
    void chatStreamDoesNotPersistWhenCancelledAfterLastPreTokenCheck() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry);
        ChatSession session = new ChatSession("cancel between final guard and persist", "owner-a", "ANON");
        session.setId(18L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        ChatRunExecutionContext commitLostRun = mock(ChatRunExecutionContext.class);
        when(commitLostRun.tryBeginTranscriptCommit()).thenReturn(false);
        when(runRegistry.beginOrJoin(18L))
                .thenReturn(new ChatRunRegistry.BeginResult(commitLostRun, true));
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("Late persistence race answer.", "mock-model", false));

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("cancel persistence race")
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().noneMatch(e ->
                e.data() != null
                        && "final".equals(e.data().type())
                        && "Late persistence race answer.".equals(e.data().data())));
        verify(historyService, never())
                .appendMessageReturningId(18L, "assistant", "Late persistence race answer.");
        verify(commitLostRun).tryBeginTranscriptCommit();
    }

    @Test
    void cancelledFirstStreamCannotPersistOrFinishReplacementStream() throws Exception {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 32);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry);
        ChatSession session = new ChatSession("R1", "owner-a", "ANON");
        session.setId(27L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(27L)).thenReturn(session);
        when(historyService.appendMessageReturningId(27L, "assistant", "R1 late answer"))
                .thenReturn(71L);
        when(historyService.appendMessageReturningId(27L, "assistant", "R2 answer"))
                .thenReturn(72L);

        CountDownLatch r1Entered = new CountDownLatch(1);
        CountDownLatch r2Entered = new CountDownLatch(1);
        CountDownLatch releaseR1 = new CountDownLatch(1);
        CountDownLatch releaseR2 = new CountDownLatch(1);
        CountDownLatch r1Finished = new CountDownLatch(1);
        CountDownLatch r2Finished = new CountDownLatch(1);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenAnswer(invocation -> {
                    ChatRequestDto request = invocation.getArgument(0);
                    if ("R1".equals(request.getMessage())) {
                        r1Entered.countDown();
                        if (!releaseR1.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("test did not release R1");
                        }
                        return ChatResult.of("R1 late answer", "mock-model", false);
                    }
                    if ("R2".equals(request.getMessage())) {
                        r2Entered.countDown();
                        if (!releaseR2.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("test did not release R2");
                        }
                        return ChatResult.of("R2 answer", "mock-model", false);
                    }
                    throw new AssertionError("unexpected request message");
                });

        reactor.core.Disposable r1Subscription = null;
        reactor.core.Disposable r2Subscription = null;
        try {
            r1Subscription = controller.chatStream(
                            ChatRequestDto.builder()
                                    .message("R1")
                                    .sessionId(27L)
                                    .useRag(false)
                                    .useWebSearch(false)
                                    .build(),
                            false,
                            false,
                            null,
                            new MockHttpServletRequest())
                    .doFinally(ignored -> r1Finished.countDown())
                    .subscribe();
            assertTrue(r1Entered.await(1, TimeUnit.SECONDS), "R1 should reach the chat service");

            assertEquals(HttpStatus.OK, controller.cancel(
                    27L,
                    null,
                    runRegistry.currentRunToken(27L).orElseThrow(),
                    null).getStatusCode());

            r2Subscription = controller.chatStream(
                            ChatRequestDto.builder()
                                    .message("R2")
                                    .sessionId(27L)
                                    .useRag(false)
                                    .useWebSearch(false)
                                    .build(),
                            false,
                            false,
                            null,
                            new MockHttpServletRequest())
                    .doFinally(ignored -> r2Finished.countDown())
                    .subscribe();
            assertTrue(r2Entered.await(1, TimeUnit.SECONDS), "R2 should replace the cancelled R1 run");

            releaseR1.countDown();
            assertTrue(r1Finished.await(3, TimeUnit.SECONDS), "R1 should finish after its answer is released");

            assertTrue(runRegistry.isRunning(27L), "late R1 completion must not finish R2");
            verify(historyService, never())
                    .appendMessageReturningId(27L, "assistant", "R1 late answer");

            releaseR2.countDown();
            assertTrue(r2Finished.await(3, TimeUnit.SECONDS), "R2 should finish after its answer is released");
            verify(historyService).appendMessageReturningId(27L, "assistant", "R2 answer");
        } finally {
            releaseR1.countDown();
            releaseR2.countDown();
            if (r1Subscription != null) {
                r1Subscription.dispose();
            }
            if (r2Subscription != null) {
                r2Subscription.dispose();
            }
            if (runRegistry.isRunning(27L)) {
                runRegistry.currentRunToken(27L)
                        .ifPresent(token -> runRegistry.cancelExact(27L, token));
            }
        }
    }

    @Test
    void attachedClientKeepsDetachedOwnerRunAndCannotContaminateReplacement() throws Exception {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 64);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        CountDownLatch firstProducerCleanup = new CountDownLatch(1);
        ChatStreamEmitter emitter = new ChatStreamEmitter() {
            @Override
            public boolean unregisterSink(
                    ChatRunExecutionContext context,
                    Sinks.Many<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> expectedSink) {
                boolean removed = super.unregisterSink(context, expectedSink);
                firstProducerCleanup.countDown();
                return removed;
            }
        };
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                null,
                emitter);
        ChatSession session = new ChatSession("R1", "owner-a", "ANON");
        session.setId(28L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(28L)).thenReturn(session);
        when(historyService.appendMessageReturningId(28L, "assistant", "R2 answer"))
                .thenReturn(82L);

        CountDownLatch r1Entered = new CountDownLatch(1);
        CountDownLatch emitAfterDetach = new CountDownLatch(1);
        CountDownLatch afterDetachEmitted = new CountDownLatch(1);
        CountDownLatch finishR1 = new CountDownLatch(1);
        CountDownLatch r2Entered = new CountDownLatch(1);
        CountDownLatch emitR2AfterCleanup = new CountDownLatch(1);
        CountDownLatch r2AfterCleanupEmitted = new CountDownLatch(1);
        CountDownLatch finishR2 = new CountDownLatch(1);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenAnswer(invocation -> {
                    ChatRequestDto request = invocation.getArgument(0);
                    if ("R1".equals(request.getMessage())) {
                        r1Entered.countDown();
                        assertTrue(emitAfterDetach.await(3, TimeUnit.SECONDS));
                        emitter.sendStatus(ChatRunExecutionContext.current(), "r1-after-detach");
                        afterDetachEmitted.countDown();
                        assertTrue(finishR1.await(3, TimeUnit.SECONDS));
                        emitter.sendStatus(ChatRunExecutionContext.current(), "late-r1-after-replacement");
                        return ChatResult.of("R1 late answer", "mock-model", false);
                    }
                    if ("R2".equals(request.getMessage())) {
                        r2Entered.countDown();
                        emitter.sendStatus(ChatRunExecutionContext.current(), "r2-own");
                        assertTrue(emitR2AfterCleanup.await(3, TimeUnit.SECONDS));
                        emitter.sendStatus(ChatRunExecutionContext.current(), "r2-after-r1-cleanup");
                        r2AfterCleanupEmitted.countDown();
                        assertTrue(finishR2.await(3, TimeUnit.SECONDS));
                        return ChatResult.of("R2 answer", "mock-model", false);
                    }
                    throw new AssertionError("unexpected request message");
                });

        reactor.core.Disposable r1 = null;
        reactor.core.Disposable attached = null;
        reactor.core.Disposable r2 = null;
        CountDownLatch replaySeen = new CountDownLatch(1);
        List<String> attachedEvents = new CopyOnWriteArrayList<>();
        List<String> r2Events = new CopyOnWriteArrayList<>();
        try {
            r1 = controller.chatStream(
                            ChatRequestDto.builder()
                                    .message("R1")
                                    .sessionId(28L)
                                    .useRag(false)
                                    .useWebSearch(false)
                                    .build(),
                            false, false, null, new MockHttpServletRequest())
                    .subscribe();
            assertTrue(r1Entered.await(1, TimeUnit.SECONDS));
            MockHttpServletRequest attachRequest = new MockHttpServletRequest();
            attachRequest.addHeader(
                    "X-Chat-Run-Token",
                    runRegistry.currentRunToken(28L).orElseThrow());
            attached = controller.chatStream(
                            ChatRequestDto.builder().message("attach").sessionId(28L).build(),
                            true, false, null, attachRequest)
                    .subscribe(event -> {
                        if (event.data() != null && event.data().data() != null) {
                            String data = String.valueOf(event.data().data());
                            attachedEvents.add(data);
                            if ("r1-after-detach".equals(data)) {
                                replaySeen.countDown();
                            }
                        }
                    });
            r1.dispose();
            emitAfterDetach.countDown();
            assertTrue(afterDetachEmitted.await(1, TimeUnit.SECONDS));
            assertTrue(replaySeen.await(1, TimeUnit.SECONDS),
                    "a remaining attached client must receive producer events after the original HTTP detach");

            assertEquals(HttpStatus.OK, controller.cancel(
                    28L,
                    null,
                    runRegistry.currentRunToken(28L).orElseThrow(),
                    null).getStatusCode());
            r2 = controller.chatStream(
                            ChatRequestDto.builder()
                                    .message("R2")
                                    .sessionId(28L)
                                    .useRag(false)
                                    .useWebSearch(false)
                                    .build(),
                            false, false, null, new MockHttpServletRequest())
                    .subscribe(event -> {
                        if (event.data() != null && event.data().data() != null) {
                            r2Events.add(String.valueOf(event.data().data()));
                        }
                    });
            assertTrue(r2Entered.await(1, TimeUnit.SECONDS));

            finishR1.countDown();
            assertTrue(firstProducerCleanup.await(3, TimeUnit.SECONDS));
            emitR2AfterCleanup.countDown();
            assertTrue(r2AfterCleanupEmitted.await(1, TimeUnit.SECONDS));

            assertTrue(r2Events.contains("r2-own"));
            assertTrue(r2Events.contains("r2-after-r1-cleanup"));
            assertFalse(r2Events.contains("late-r1-after-replacement"));
            assertFalse(attachedEvents.contains("r2-own"),
                    "an attach snapshot for R1 must not jump to replacement R2");
            assertFalse(attachedEvents.contains("r2-after-r1-cleanup"));

            finishR2.countDown();
        } finally {
            emitAfterDetach.countDown();
            finishR1.countDown();
            emitR2AfterCleanup.countDown();
            finishR2.countDown();
            if (r1 != null) r1.dispose();
            if (attached != null) attached.dispose();
            if (r2 != null) r2.dispose();
            if (runRegistry.isRunning(28L)) {
                runRegistry.currentRunToken(28L)
                        .ifPresent(token -> runRegistry.cancelExact(28L, token));
            }
        }
    }

    @Test
    void twoNonAttachStreamsForSameSessionHaveSingleExecutionOwner() throws Exception {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 32);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);
        ChatSession session = new ChatSession("R1 owner", "owner-a", "ANON");
        session.setId(29L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(29L)).thenReturn(session);
        when(historyService.appendMessageReturningId(29L, "assistant", "R1 answer"))
                .thenReturn(91L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenAnswer(invocation -> {
                    executions.incrementAndGet();
                    entered.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                    return ChatResult.of("R1 answer", "mock-model", false);
                });

        reactor.core.Disposable owner = null;
        reactor.core.Disposable joiner = null;
        try {
            owner = controller.chatStream(
                            ChatRequestDto.builder().message("R1 owner").sessionId(29L).build(),
                            false, false, null, new MockHttpServletRequest())
                    .subscribe();
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            joiner = controller.chatStream(
                            ChatRequestDto.builder().message("R2 must join").sessionId(29L).build(),
                            false, false, null, new MockHttpServletRequest())
                    .subscribe();

            assertEquals(1, executions.get());
            verify(chatService, times(1)).continueChat(any(ChatRequestDto.class), any());
        } finally {
            release.countDown();
            if (owner != null) owner.dispose();
            if (joiner != null) joiner.dispose();
            if (runRegistry.isRunning(29L)) {
                runRegistry.currentRunToken(29L)
                        .ifPresent(token -> runRegistry.cancelExact(29L, token));
            }
        }
    }

    @Test
    void prestartedRunIsFinishedWhenWorkerFailsBeforeReplayBridge() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 32);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);
        ChatSession session = new ChatSession("pre-bridge failure", "owner-a", "ANON");
        session.setId(30L);

        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(30L)).thenReturn(session);
        when(settingsService.getAllSettings())
                .thenReturn(java.util.Map.of())
                .thenReturn(java.util.Map.of())
                .thenThrow(new IllegalStateException("fixture failure"));

        var events = controller.chatStream(
                        ChatRequestDto.builder().message("fail before bridge").sessionId(30L).build(),
                        false, false, null, new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertFalse(runRegistry.isRunning(30L),
                "an owner run must not remain stuck when setup fails before the replay bridge exists");
        verifyNoInteractions(chatService);
    }

    @Test
    void exactAttachDoesNotRevealWhetherSessionExists() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);
        ChatSession foreign = new ChatSession("foreign", "owner-a", "ANON");
        foreign.setId(90L);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-b");
        when(historyService.getSessionWithMessages(90L)).thenReturn(foreign);

        MockHttpServletRequest foreignRequest = new MockHttpServletRequest();
        foreignRequest.addHeader("X-Chat-Run-Token", "opaque-invalid-token");
        MockHttpServletRequest missingRequest = new MockHttpServletRequest();
        missingRequest.addHeader("X-Chat-Run-Token", "opaque-invalid-token");

        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> foreignEvents = controller.chatStream(
                        ChatRequestDto.builder().message("attach").sessionId(90L).build(),
                        true, false, null, foreignRequest)
                .collectList().block(Duration.ofSeconds(1));
        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> missingEvents = controller.chatStream(
                        ChatRequestDto.builder().message("attach").sessionId(91L).build(),
                        true, false, null, missingRequest)
                .collectList().block(Duration.ofSeconds(1));
        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> foreignTokenlessEvents =
                controller.chatStream(
                                ChatRequestDto.builder().message("attach").sessionId(90L).build(),
                                true, false, null, new MockHttpServletRequest())
                        .collectList().block(Duration.ofSeconds(1));
        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> missingTokenlessEvents =
                controller.chatStream(
                                ChatRequestDto.builder().message("attach").sessionId(91L).build(),
                                true, false, null, new MockHttpServletRequest())
                        .collectList().block(Duration.ofSeconds(1));

        assertNotNull(foreignEvents);
        assertNotNull(missingEvents);
        assertNotNull(foreignTokenlessEvents);
        assertNotNull(missingTokenlessEvents);
        assertEquals(1, foreignEvents.size());
        assertEquals(1, missingEvents.size());
        assertEquals(1, foreignTokenlessEvents.size());
        assertEquals(1, missingTokenlessEvents.size());
        assertEquals("run_not_found_or_replaced", foreignEvents.get(0).data().data());
        assertEquals(foreignEvents.get(0).data().data(), missingEvents.get(0).data().data());
        assertEquals("run_not_found_or_replaced", foreignTokenlessEvents.get(0).data().data());
        assertEquals(foreignTokenlessEvents.get(0).data().data(),
                missingTokenlessEvents.get(0).data().data());
        verifyNoInteractions(chatService);
    }

    @Test
    void exactRunAcknowledgementReleasesTheServerGenerationGate() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatSession session = new ChatSession("ack", "owner-a", "ANON");
        session.setId(92L);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(92L)).thenReturn(session);
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);
        ChatRunRegistry.BeginResult run = runRegistry.beginOrJoin(92L);

        ResponseEntity<Map<String, Object>> response = controller.acknowledgeRun(
                null,
                Map.of("sessionId", 92L, "runToken", run.context().clientToken()),
                null,
                null);

        assertEquals(true, response.getBody().get("acknowledged"));
        assertTrue(run.context().awaitClientAcknowledgement(1L));
        assertFalse(runRegistry.cancelIfUnacknowledged(run.context()));
        assertTrue(runRegistry.cancelExact(92L, run.context().clientToken()));
        verifyNoInteractions(chatService);
    }

    @Test
    void finalAcknowledgementMarksOnlyTheAuthorizedExactPersistedRunDelivered() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatSession session = new ChatSession("final ack", "owner-a", "ANON");
        session.setId(94L);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(94L)).thenReturn(session);
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);
        ChatRunExecutionContext run = runRegistry.beginOrJoin(94L).context();
        assertTrue(run.markGenerationSucceeded());
        assertTrue(run.tryBeginTranscriptCommit());
        assertTrue(run.markPersisted());
        assertTrue(run.claimTerminalEvent("delivery_pending"));
        assertTrue(run.recordFinalEmit("OK", false));

        ResponseEntity<Map<String, Object>> response = controller.acknowledgeRun(
                null,
                Map.of(
                        "sessionId", 94L,
                        "runToken", run.clientToken(),
                        "phase", "final"),
                null,
                null);

        assertEquals(true, response.getBody().get("acknowledged"));
        assertEquals("final", response.getBody().get("phase"));
        assertEquals("final_acknowledged", response.getBody().get("reason"));
        ChatRunRegistry.RunOutcomeView outcome = runRegistry
                .describeExact(94L, run.clientToken()).orElseThrow().outcome();
        assertTrue(outcome.finalDeliveryAccepted());
        assertEquals("none", outcome.finalDeliveryFailureReason());
        assertTrue(runRegistry.markDone(run));
        verifyNoInteractions(chatService);
    }

    @Test
    void acknowledgementWinningAfterWaitTimeoutStillAllowsGeneration() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry() {
            @Override
            public boolean cancelIfUnacknowledged(
                    ChatRunExecutionContext context,
                    List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> terminalEvidence) {
                assertTrue(acknowledgeExact(93L, context.clientToken()),
                        "the fixture ACK must win immediately after the bounded wait");
                return super.cancelIfUnacknowledged(context, terminalEvidence);
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);
        ChatSession session = new ChatSession("late ack", "owner-a", "ANON");
        session.setId(93L);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(historyService.getSessionWithMessages(93L)).thenReturn(session);
        when(historyService.appendMessageReturningId(93L, "assistant", "late ACK answer"))
                .thenReturn(93L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("late ACK answer", "mock-model", false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Chat-Run-Ack-Required", "1");

        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("late ACK must not produce an empty run")
                                .sessionId(93L)
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        false, false, null, request)
                .collectList()
                .block(Duration.ofSeconds(6));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event -> event.data() != null
                        && "final".equals(event.data().type())
                        && "late ACK answer".equals(event.data().data())),
                "a late ACK that wins cancellation must continue the same run");
        verify(chatService, times(1)).continueChat(any(ChatRequestDto.class), any());
    }

    @Test
    void chatSyncDoesNotAppendFirstUserTurnAgainForNewSessionReloadTranscript() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ChatSession session = new ChatSession("new sync memory", "owner-a", "ANON");
        session.setId(21L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(historyService.appendMessageReturningId(21L, "assistant", "NOVA-716"))
                .thenReturn(57L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("NOVA-716", "mock-model", false));

        var response = controller.chatSync(
                ChatRequestDto.builder()
                        .message("new sync memory")
                        .useWebSearch(false)
                        .build(),
                null, new MockHttpServletRequest());

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(historyService, never()).appendMessage(21L, "user", "new sync memory");
        ArgumentCaptor<ChatRequestDto> dtoCaptor = ArgumentCaptor.forClass(ChatRequestDto.class);
        verify(chatService).continueChat(dtoCaptor.capture(), any());
        ChatRequestDto.RetrievalRequestIntent intent = dtoCaptor.getValue().getRetrievalRequestIntent();
        assertNotNull(intent);
        assertEquals(Boolean.FALSE, intent.webSearch());
        assertNull(intent.rag());
    }

    @Test
    void chatSyncPrefetchUsesFactualQueryForForceLightProbeWording() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        ChatRunRegistry registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                registry,
                webSearchProvider);
        ChatSession session = new ChatSession("light query prefetch", "owner-a", "ANON");
        session.setId(22L);
        String noisyProbe = "LIGHT 검색 점검: 대한민국 수도를 한 문장으로 답하고 검색/증거 상태를 짧게 말해줘.";

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(webSearchProvider.searchWithTrace(any(), anyInt()))
                .thenReturn(new NaverSearchService.SearchResult(List.of("대한민국 수도는 서울입니다."), null));
        when(historyService.appendMessageReturningId(22L, "assistant", "서울"))
                .thenReturn(61L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("서울", "mock-model", true));

        var response = controller.chatSync(
                ChatRequestDto.builder()
                        .message(noisyProbe)
                        .searchMode(SearchMode.FORCE_LIGHT)
                        .useRag(false)
                        .useWebSearch(true)
                        .build(),
                null, new MockHttpServletRequest());

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(webSearchProvider).searchWithTrace(queryCaptor.capture(), anyInt());
        assertEquals("대한민국 수도", queryCaptor.getValue());
    }

    @Test
    void chatStreamPrefetchUsesFactualQueryForForceLightProbeWording() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                webSearchProvider);
        ChatSession session = new ChatSession("light stream query prefetch", "owner-a", "ANON");
        session.setId(23L);
        String noisyProbe = "LIGHT 검색 점검: 대한민국 수도를 한 문장으로 답하고 검색/증거 상태를 짧게 말해줘.";

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 23L);
        when(webSearchProvider.searchWithTrace(any(), anyInt()))
                .thenReturn(new NaverSearchService.SearchResult(List.of("대한민국 수도는 서울입니다."), null));
        when(historyService.appendMessageReturningId(23L, "assistant", "서울"))
                .thenReturn(62L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("서울", "mock-model", true));

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message(noisyProbe)
                                .searchMode(SearchMode.FORCE_LIGHT)
                                .useRag(false)
                                .useWebSearch(true)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())));
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(webSearchProvider).searchWithTrace(queryCaptor.capture(), anyInt());
        assertEquals("대한민국 수도", queryCaptor.getValue());
    }

    @Test
    void chatStreamPrefetchRemovesShortAnswerInstructionFromForceLightProbeWording() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                webSearchProvider);
        ChatSession session = new ChatSession("short answer light stream query prefetch", "owner-a", "ANON");
        session.setId(24L);
        String noisyProbe = "LIGHT 검색 점검: 대한민국 수도를 한 문장으로 아주 짧게 답해줘.";

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 24L);
        when(webSearchProvider.searchWithTrace(any(), anyInt()))
                .thenReturn(new NaverSearchService.SearchResult(List.of("대한민국 수도는 서울입니다."), null));
        when(historyService.appendMessageReturningId(24L, "assistant", "서울"))
                .thenReturn(63L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("서울", "mock-model", true));

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message(noisyProbe)
                                .searchMode(SearchMode.FORCE_LIGHT)
                                .useRag(false)
                                .useWebSearch(true)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())));
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(webSearchProvider).searchWithTrace(queryCaptor.capture(), anyInt());
        assertEquals("대한민국 수도", queryCaptor.getValue());
    }

    @Test
    void chatStreamForceLightUsesRequestedQueryAfterPrefetchInvalidation() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                webSearchProvider);
        ChatSession session = new ChatSession("force light empty prefetch anchor", "owner-a", "ANON");
        session.setId(25L);
        String noisyProbe = "LIGHT 검색 점검: 대한민국 수도를 한 문장으로 아주 짧게 답해줘.";
        java.util.concurrent.atomic.AtomicReference<List<String>> supplied = new java.util.concurrent.atomic.AtomicReference<>();

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 25L);
        when(webSearchProvider.searchWithTrace(any(), anyInt()))
                .thenReturn(new NaverSearchService.SearchResult(List.of(), null));
        when(webSearchProvider.search(any(), anyInt()))
                .thenReturn(List.of("대한민국 수도는 서울입니다."));
        when(historyService.appendMessageReturningId(25L, "assistant", "서울"))
                .thenReturn(64L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Function<String, List<String>> supplier = invocation.getArgument(1);
                    GuardContextHolder.get().setOfficialOnly(true);
                    supplied.set(supplier.apply(noisyProbe));
                    return ChatResult.of("서울", "mock-model", true);
                });

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message(noisyProbe)
                                .searchMode(SearchMode.FORCE_LIGHT)
                                .useRag(false)
                                .useWebSearch(true)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())));
        assertEquals(List.of("대한민국 수도는 서울입니다."), supplied.get());
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(webSearchProvider).search(queryCaptor.capture(), anyInt());
        assertEquals("대한민국 수도", queryCaptor.getValue());
    }

    @Test
    void chatStreamInfersForceLightFromProbePrefixWhenModeFallsBackToAuto() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                webSearchProvider);
        ChatSession session = new ChatSession("force light prefix fallback", "owner-a", "ANON");
        session.setId(26L);
        String noisyProbe = "LIGHT 검색 점검: 대한민국 수도를 한 문장으로 아주 짧게 답해줘.";

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 26L);
        when(webSearchProvider.searchWithTrace(any(), anyInt()))
                .thenReturn(new NaverSearchService.SearchResult(List.of("대한민국 수도는 서울입니다."), null));
        when(historyService.appendMessageReturningId(26L, "assistant", "서울"))
                .thenReturn(65L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("서울", "mock-model", true));

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message(noisyProbe)
                                .searchMode(SearchMode.AUTO)
                                .useRag(false)
                                .useWebSearch(true)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())));
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(webSearchProvider).searchWithTrace(queryCaptor.capture(), anyInt());
        assertEquals("대한민국 수도", queryCaptor.getValue());
        ArgumentCaptor<ChatRequestDto> dtoCaptor = ArgumentCaptor.forClass(ChatRequestDto.class);
        verify(chatService).continueChat(dtoCaptor.capture(), any());
        assertEquals(SearchMode.FORCE_LIGHT, dtoCaptor.getValue().getSearchMode());
        assertNotNull(dtoCaptor.getValue().getRetrievalRequestIntent());
        assertEquals(Boolean.TRUE, dtoCaptor.getValue().getRetrievalRequestIntent().webSearch());
        assertEquals(Boolean.FALSE, dtoCaptor.getValue().getRetrievalRequestIntent().rag());
    }

    @Test
    void chatStreamEmitsWebSearchStatusBeforeBlockingSearchReturns() throws Exception {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                webSearchProvider);
        ChatSession session = new ChatSession("web status", "owner-a", "ANON");
        session.setId(15L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 15L);
        when(historyService.appendMessageReturningId(15L, "assistant", "Web answer is ready."))
                .thenReturn(58L);
        CountDownLatch searchEntered = new CountDownLatch(1);
        CountDownLatch searchStatusSeen = new CountDownLatch(1);
        CountDownLatch releaseSearch = new CountDownLatch(1);
        CountDownLatch finalSeen = new CountDownLatch(1);
        when(webSearchProvider.searchWithTrace(any(), anyInt()))
                .thenAnswer(invocation -> {
                    searchEntered.countDown();
                    if (!releaseSearch.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release delayed search");
                    }
                    return new NaverSearchService.SearchResult(List.of("safe web snippet"), null);
                });
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("Web answer is ready.", "gemma3:4b", true));

        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> events = new CopyOnWriteArrayList<>();
        var subscription = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("What changed today?")
                                .searchMode(SearchMode.AUTO)
                                .useRag(true)
                                .useWebSearch(true)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .subscribe(event -> {
                    events.add(event);
                    ChatStreamEvent data = event.data();
                    ChatStreamEvent.StatusSignal signal = data == null ? null : data.statusSignal();
                    if (signal != null && "web_search_running".equals(signal.code())) {
                        searchStatusSeen.countDown();
                    }
                    if (data != null && "final".equals(data.type())) {
                        finalSeen.countDown();
                    }
                });

        try {
            assertTrue(searchEntered.await(1, TimeUnit.SECONDS), "web search should be running in background");
            assertTrue(searchStatusSeen.await(1, TimeUnit.SECONDS),
                    "stream should expose web search progress before a slow provider returns");
            releaseSearch.countDown();
            assertTrue(finalSeen.await(3, TimeUnit.SECONDS), "final event should arrive after delayed search");
            assertTrue(indexOf(events, "status", "web_search_running") >= 0);
        } finally {
            releaseSearch.countDown();
            subscription.dispose();
        }
    }

    @Test
    void chatStreamCrossQueryPrefetchFailureReturnsEmptyInsteadOfStaleEvidence() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                webSearchProvider);
        ChatSession session = new ChatSession("What changed today?", "owner-a", "ANON");
        session.setId(16L);
        List<String> prefetched = List.of("prefetched safe snippet");
        java.util.concurrent.atomic.AtomicReference<List<String>> supplied = new java.util.concurrent.atomic.AtomicReference<>();

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 16L);
        when(webSearchProvider.searchWithTrace(any(), anyInt()))
                .thenReturn(new NaverSearchService.SearchResult(prefetched, null));
        when(webSearchProvider.search(any(), anyInt()))
                .thenThrow(new IllegalStateException("redacted failure"));
        when(historyService.appendMessageReturningId(16L, "assistant", "Reuse answer is ready."))
                .thenReturn(59L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Function<String, List<String>> supplier = invocation.getArgument(1);
                    supplied.set(supplier.apply("changed guard query"));
                    return ChatResult.of("Reuse answer is ready.", "gemma3:4b", true);
                });

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("What changed today?")
                                .searchMode(SearchMode.AUTO)
                                .useRag(true)
                                .useWebSearch(true)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())));
        assertEquals(List.of(), supplied.get(),
                "a failed cross-query retry must not fall back to stale prefetch evidence");
        verify(webSearchProvider).searchWithTrace(any(), anyInt());
        verify(webSearchProvider).search(eq("changed guard query"), anyInt());
    }

    @Test
    void chatStreamRejectsPrefetchWhenFilterScopeChanges() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                webSearchProvider);
        ChatSession session = new ChatSession("force light prefetch reuse", "owner-a", "ANON");
        session.setId(17L);
        List<String> prefetched = List.of("prefetched light snippet");
        java.util.concurrent.atomic.AtomicReference<List<String>> supplied = new java.util.concurrent.atomic.AtomicReference<>();

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 17L);
        when(webSearchProvider.searchWithTrace(any(), anyInt()))
                .thenReturn(new NaverSearchService.SearchResult(prefetched, null));
        when(webSearchProvider.search(any(), anyInt()))
                .thenReturn(List.of("fresh light snippet"));
        when(historyService.appendMessageReturningId(17L, "assistant", "Light reuse answer is ready."))
                .thenReturn(60L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Function<String, List<String>> supplier = invocation.getArgument(1);
                    GuardContextHolder.get().setOfficialOnly(true);
                    supplied.set(supplier.apply("force light prefetch reuse"));
                    return ChatResult.of("Light reuse answer is ready.", "gemma3:4b", true);
                });

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("force light prefetch reuse")
                                .searchMode(SearchMode.FORCE_LIGHT)
                                .useRag(true)
                                .useWebSearch(true)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())));
        assertEquals(List.of("fresh light snippet"), supplied.get(),
                "FORCE_LIGHT must search again when the prefetch filter scope changes");
        verify(webSearchProvider).searchWithTrace(any(), anyInt());
        verify(webSearchProvider).search(eq("force light prefetch reuse"), anyInt());
    }

    @Test
    void publicChatSyncPathRejectsSingleCharacterCrossQueryPrefetch() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                webSearchProvider);
        ChatSession session = new ChatSession("C language", "owner-a", "ANON");
        session.setId(18L);
        java.util.concurrent.atomic.AtomicReference<List<String>> supplied = new java.util.concurrent.atomic.AtomicReference<>();

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 18L);
        when(webSearchProvider.searchWithTrace(eq("C language"), anyInt()))
                .thenReturn(new NaverSearchService.SearchResult(List.of("prefetched C evidence"), null));
        when(webSearchProvider.search(eq("R language"), anyInt()))
                .thenReturn(List.of("fresh R evidence"));
        when(historyService.appendMessageReturningId(18L, "assistant", "R answer"))
                .thenReturn(61L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Function<String, List<String>> supplier = invocation.getArgument(1);
                    supplied.set(supplier.apply("R language"));
                    return ChatResult.of("R answer", "mock-model", true);
                });

        ResponseEntity<ChatResponseDto> response = controller.chat(
                        ChatRequestDto.builder()
                                .message("C language")
                                .searchMode(SearchMode.FORCE_LIGHT)
                                .useRag(true)
                                .useWebSearch(true)
                                .build(),
                        null,
                        new MockHttpServletRequest())
                .block(Duration.ofSeconds(5));

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of("fresh R evidence"), supplied.get());
        verify(webSearchProvider).searchWithTrace(eq("C language"), anyInt());
        verify(webSearchProvider).search(eq("R language"), anyInt());
    }

    @Test
    void chatStreamPersistsUiSettingsIntoSessionMetaForReloadContinuity() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry);
        ChatSession session = new ChatSession("stream settings", "owner-a", "ANON");
        session.setId(14L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 14L);
        when(historyService.appendMessageReturningId(14L, "assistant", "Settings answer is ready."))
                .thenReturn(57L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("Settings answer is ready.", "gemma3:4b", false));

        var events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("stream settings")
                                .model("gemma3:4b")
                                .searchMode(SearchMode.OFF)
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())));
        String meta = session.getSessionMeta();
        assertNotNull(meta);
        assertTrue(meta.contains("\"model\":\"gemma3:4b\""), meta);
        assertTrue(meta.contains("\"searchMode\":\"OFF\""), meta);
        assertTrue(meta.contains("\"useRag\":false"), meta);
        verify(historyService).updateSessionMeta(eq(14L), argThat(saved ->
                "gemma3:4b".equals(saved.get("model"))
                        && "OFF".equals(String.valueOf(saved.get("searchMode")))
                        && Boolean.FALSE.equals(saved.get("useRag"))));
    }

    @Test
    void chatStreamEmitsDefaultModelWaitStatusBeforeAnswerTokens() throws Exception {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        ChatApiController controller = controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry);
        ChatSession session = new ChatSession("slow default model", "owner-a", "ANON");
        session.setId(13L);

        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        stubOwnerRun(runRegistry, 13L);
        when(historyService.appendMessageReturningId(13L, "assistant", "Delayed answer arrived."))
                .thenReturn(56L);
        CountDownLatch serviceEntered = new CountDownLatch(1);
        CountDownLatch waitStatusSeen = new CountDownLatch(1);
        CountDownLatch releaseAnswer = new CountDownLatch(1);
        CountDownLatch finalSeen = new CountDownLatch(1);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenAnswer(invocation -> {
                    serviceEntered.countDown();
                    if (!releaseAnswer.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release delayed answer");
                    }
                    return ChatResult.of("Delayed answer arrived.", "gemma4:26b", false);
                });

        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> events = new CopyOnWriteArrayList<>();
        var subscription = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("slow default model")
                                .model("gemma4:26b")
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        false,
                        false,
                        null,
                        new MockHttpServletRequest())
                .subscribe(event -> {
                    events.add(event);
                    ChatStreamEvent data = event.data();
                    if (data != null
                            && data.statusSignal() != null
                            && "waiting_for_default_model".equals(data.statusSignal().code())) {
                        waitStatusSeen.countDown();
                    }
                    if (data != null && "final".equals(data.type())) {
                        finalSeen.countDown();
                    }
                });

        assertTrue(serviceEntered.await(1, TimeUnit.SECONDS), "chat service should be running in background");
        assertTrue(waitStatusSeen.await(1, TimeUnit.SECONDS),
                "stream should tell the user that the default model is still running while the service is pending");
        int waitStatus = indexOf(events, "status", "waiting_for_default_model");
        int firstToken = indexOf(events, "token", null);
        assertTrue(waitStatus >= 0);
        assertEquals(-1, firstToken, "answer tokens should not be emitted before the delayed answer arrives");

        releaseAnswer.countDown();
        assertTrue(finalSeen.await(3, TimeUnit.SECONDS), "final event should arrive after delayed answer");
        subscription.dispose();
        firstToken = indexOf(events, "token", null);
        assertTrue(firstToken > waitStatus, "wait status should arrive before answer tokens");
    }

    private static int indexOf(List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> events,
                               String type,
                               String code) {
        for (int i = 0; i < events.size(); i++) {
            ChatStreamEvent event = events.get(i).data();
            if (event == null || !type.equals(event.type())) {
                continue;
            }
            if (code == null) {
                return i;
            }
            ChatStreamEvent.StatusSignal signal = event.statusSignal();
            if (signal != null && code.equals(signal.code())) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void attachWithoutExactRunTokenNeverStartsGeneration() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatSession session = new ChatSession("active", "owner-a", "ANON");
        session.setId(140L);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(140L)).thenReturn(session);
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);
        ChatRunRegistry.BeginResult active = runRegistry.beginOrJoin(140L);

        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> events = controller.chatStream(
                        ChatRequestDto.builder().message("attach").sessionId(140L).build(),
                        true, false, null, new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals(1, events.size());
        assertEquals("run_not_found_or_replaced", events.get(0).data().data());
        assertTrue(runRegistry.isRunning(140L));
        verifyNoInteractions(chatService);
        runRegistry.cancelExact(140L, active.context().clientToken());
    }

    @Test
    void terminalAttachByTokenReplaysFirstRunAfterReplacementWithoutGeneration() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatSession session = new ChatSession("terminal", "owner-a", "ANON");
        session.setId(141L);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(141L)).thenReturn(session);
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);

        ChatRunRegistry.BeginResult first = runRegistry.beginOrJoin(141L);
        runRegistry.emit(first.context(), org.springframework.http.codec.ServerSentEvent.builder(
                ChatStreamEvent.status("r1-terminal")).build());
        runRegistry.markDone(first.context());
        ChatRunRegistry.BeginResult replacement = runRegistry.beginOrJoin(141L);

        MockHttpServletRequest attachRequest = new MockHttpServletRequest();
        attachRequest.addHeader("X-Chat-Run-Token", first.context().clientToken());
        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> events = controller.chatStream(
                        ChatRequestDto.builder().message("attach").sessionId(141L).build(),
                        true, false, null, attachRequest)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals(1, events.size());
        assertEquals("r1-terminal", events.get(0).data().data());
        assertTrue(runRegistry.isRunning(141L));
        verifyNoInteractions(chatService);
        runRegistry.cancelExact(141L, replacement.context().clientToken());
    }

    @Test
    void workerFailureBeforeTraceAttachFinishesPrestartedRunAndCompletesFlux() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatSession session = new ChatSession("pre-init", "owner-a", "ANON");
        session.setId(142L);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(142L)).thenReturn(session);
        ChatApiController controller = spy(controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry));
        doThrow(new IllegalStateException("trace attach fixture"))
                .when(controller).attachStreamTraceContext(any(), any());

        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> events = controller.chatStream(
                        ChatRequestDto.builder().message("pre-init").sessionId(142L).build(),
                        false, false, null, new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event -> event.data() != null
                        && "error".equals(event.data().type())),
                "a pre-bridge lifecycle failure must be visible on the connected client flux");
        assertFalse(runRegistry.isRunning(142L));
        verifyNoInteractions(chatService);
    }

    @Test
    void ackRequiredDisconnectBeforeSessionReadyCancelsBeforeModelGeneration() throws Exception {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);
        ChatSession created = new ChatSession("new", "owner-a", "ANON");
        created.setId(145L);
        CountDownLatch sessionCreationEntered = new CountDownLatch(1);
        CountDownLatch releaseSessionCreation = new CountDownLatch(1);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(historyService.startNewSession(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            sessionCreationEntered.countDown();
            assertTrue(releaseSessionCreation.await(3, TimeUnit.SECONDS));
            return Optional.of(created);
        });
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Chat-Run-Ack-Required", "1");

        reactor.core.Disposable client = controller.chatStream(
                        ChatRequestDto.builder().message("disconnect before identity").build(),
                        false, false, null, request)
                .subscribe();
        assertTrue(sessionCreationEntered.await(3, TimeUnit.SECONDS));
        client.dispose();
        releaseSessionCreation.countDown();

        ChatRunRegistry.RunView terminal = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && terminal == null) {
            Optional<String> token = runRegistry.currentRunToken(145L);
            if (token.isPresent()) {
                ChatRunRegistry.RunView view = runRegistry.describeExact(145L, token.get()).orElse(null);
                if (view != null && view.terminal()) terminal = view;
            }
            if (terminal == null) Thread.sleep(10L);
        }

        assertNotNull(terminal, "the unacknowledged run should become terminal");
        assertEquals(ChatRunRegistry.Status.CANCELLED, terminal.status());
        assertFalse(runRegistry.isRunning(145L));
        verifyNoInteractions(chatService);
    }

    @Test
    void recoveredSessionBindsRunIdentityOnlyToCanonicalSessionId() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "replayCapacity", 32);
        org.springframework.test.util.ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatSession recovered = new ChatSession("recovered", "owner-a", "ANON");
        recovered.setId(144L);
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(historyService.getSessionWithMessages(143L)).thenReturn(null);
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(recovered));
        when(historyService.appendMessageReturningId(144L, "assistant", "recovered answer"))
                .thenReturn(1441L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("recovered answer", "mock-model", false));
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);

        List<org.springframework.http.codec.ServerSentEvent<ChatStreamEvent>> events = controller.chatStream(
                        ChatRequestDto.builder()
                                .message("recover")
                                .sessionId(143L)
                                .useRag(false)
                                .useWebSearch(false)
                                .build(),
                        false, false, null, new MockHttpServletRequest())
                .collectList()
                .block(Duration.ofSeconds(5));

        ChatStreamEvent sessionEvent = events.stream()
                .map(org.springframework.http.codec.ServerSentEvent::data)
                .filter(event -> event != null && "session".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(144L, sessionEvent.sessionId());
        assertTrue(runRegistry.attachExact(144L, sessionEvent.data()).isPresent());
        assertFalse(runRegistry.attachExact(143L, sessionEvent.data()).isPresent());
        assertFalse(runRegistry.isRunning(143L));
        verify(historyService).appendMessageReturningId(144L, "assistant", "recovered answer");
        verify(historyService, never()).appendMessage(144L, "user", "recover");
    }

    @Test
    void chatSyncRecoveredSessionDoesNotAppendFirstUserTurnAgain() {
        assertResolvedSessionUserAppend(901L, 902L, false, true);
    }

    @Test
    void chatSyncRecoveryDoesNotInferCreationFromIdInequality() {
        assertResolvedSessionUserAppend(901L, 901L, false, true);
    }

    @Test
    void chatSyncExistingOwnedSessionAppendsExactlyOneUserTurn() {
        assertResolvedSessionUserAppend(902L, 902L, true, true);
    }

    @Test
    void publicChatRecoveredSessionDoesNotAppendFirstUserTurnAgain() {
        assertResolvedSessionUserAppend(901L, 902L, false, false);
    }

    private static void assertResolvedSessionUserAppend(
            Long requestedId, long resolvedId, boolean existing, boolean synchronous) {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver);
        ChatSession session = new ChatSession("session recovery", "owner-a", "ANON");
        session.setId(resolvedId);
        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(requestedId)).thenReturn(existing ? session : null);
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(historyService.appendMessageReturningId(resolvedId, "assistant", "recovery answer"))
                .thenReturn(9021L);
        when(chatService.continueChat(any(ChatRequestDto.class), any()))
                .thenReturn(ChatResult.of("recovery answer", "mock-model", false));
        ChatRequestDto request = ChatRequestDto.builder()
                .message("recover first turn")
                .sessionId(requestedId)
                .useRag(false)
                .useWebSearch(false)
                .build();

        ResponseEntity<ChatResponseDto> response = synchronous
                ? controller.chatSync(request, null, new MockHttpServletRequest())
                : controller.chat(request, null, new MockHttpServletRequest()).block(Duration.ofSeconds(5));

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(resolvedId, response.getBody().getSessionId());
        verify(historyService, times(existing ? 0 : 1)).startNewSession(
                eq("recover first turn"), any(), any(), eq("owner-a"), any());
        verify(historyService, times(existing ? 1 : 0)).appendMessage(resolvedId, "user", "recover first turn");
        verify(historyService).appendMessageReturningId(resolvedId, "assistant", "recovery answer");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "sync,false", "sync,true", "chat,false", "chat,true", "stream,false", "stream,true"
    })
    void newlyCreatedSessionAttachmentsAreDiscoverableBeforeGeneration(String route, boolean recovered) {
        assertNewSessionAttachmentDiscovery(route, recovered, "owned");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "sync,foreign", "chat,foreign", "stream,foreign",
            "sync,linked", "chat,linked", "stream,linked"
    })
    void recoveredSessionCannotClaimForeignOrPreviouslyLinkedAttachments(String route, String boundary) {
        assertNewSessionAttachmentDiscovery(route, true, boundary);
    }

    private static void assertNewSessionAttachmentDiscovery(String route, boolean recovered, String boundary) {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        ChatRunRegistry runRegistry = new ChatRunRegistry();
        ReflectionTestUtils.setField(runRegistry, "replayCapacity", 32);
        ReflectionTestUtils.setField(runRegistry, "ttlSeconds", 60);
        ChatApiController controller = controller(
                historyService, chatService, settingsService, ownerKeyResolver, runRegistry);
        com.example.lms.storage.LocalFileStorageService storage =
                mock(com.example.lms.storage.LocalFileStorageService.class);
        when(storage.save(any(), eq("chat"))).thenReturn("/uploads/chat/session-association.txt");
        AttachmentService attachments = new AttachmentService(storage, new com.example.lms.file.FileIngestionService());
        ReflectionTestUtils.setField(controller, "attachmentService", attachments);
        AttachmentOwnerIdentity owner = AttachmentOwnerIdentity.forAnonymous("owner-a");
        AttachmentOwnerIdentity fileOwner = "foreign".equals(boundary)
                ? AttachmentOwnerIdentity.forAnonymous("owner-b") : owner;
        var saved = attachments.saveAll(List.of(new org.springframework.mock.web.MockMultipartFile(
                "files", "association.txt", "text/plain", new byte[] {1})), fileOwner).get(0);
        if ("linked".equals(boundary)) {
            assertTrue(attachments.attachToSession("900", List.of(saved.id()), owner));
        }
        assertTrue(attachments.findBySession("902", owner).isEmpty());
        ChatSession session = new ChatSession("attachment association", "owner-a", "ANON");
        session.setId(902L);
        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.getSessionWithMessages(901L)).thenReturn(null);
        when(historyService.startNewSession(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(session));
        when(historyService.appendMessageReturningId(902L, "assistant", "attachment answer"))
                .thenReturn(9021L);
        AtomicInteger visibleAtGeneration = new AtomicInteger(-1);
        when(chatService.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
            visibleAtGeneration.set(attachments.findBySession("902", owner).size());
            return ChatResult.of("attachment answer", "mock-model", false);
        });
        ChatRequestDto request = ChatRequestDto.builder()
                .message("use the uploaded document")
                .sessionId(recovered ? 901L : null)
                .attachmentIds(List.of(saved.id()))
                .useRag(false)
                .useWebSearch(false)
                .build();
        if ("stream".equals(route)) {
            var events = controller.chatStream(request, false, false, null, new MockHttpServletRequest())
                    .collectList().block(Duration.ofSeconds(5));
            assertNotNull(events);
            assertTrue(events.stream().map(org.springframework.http.codec.ServerSentEvent::data)
                    .anyMatch(event -> event != null && "session".equals(event.type())
                            && Long.valueOf(902L).equals(event.sessionId())));
        } else {
            ResponseEntity<ChatResponseDto> response = "sync".equals(route)
                    ? controller.chatSync(request, null, new MockHttpServletRequest())
                    : controller.chat(request, null, new MockHttpServletRequest()).block(Duration.ofSeconds(5));
            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(902L, response.getBody().getSessionId());
        }
        int expected = "owned".equals(boundary) ? 1 : 0;
        assertEquals(expected, visibleAtGeneration.get(), "session discovery must be ready before generation");
        assertEquals(expected, attachments.findBySession("902", owner).size(),
                "later turns must discover the same owner-scoped attachments without explicit IDs");
        assertTrue(attachments.findBySession("901", owner).isEmpty());
        assertTrue(attachments.find(saved.id(), fileOwner).isPresent());
        if ("linked".equals(boundary)) {
            assertEquals(1, attachments.findBySession("900", owner).size());
        }
        verify(historyService, never()).appendMessage(902L, "user", request.getMessage());
        verify(historyService).appendMessageReturningId(902L, "assistant", "attachment answer");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "zero_break,true,NONE", "zero_break,false,NONE",
            "zero_break.v1,true,MEMORY", "zero_break.v1,false,MEMORY"})
    @org.junit.jupiter.api.Timeout(15)
    @SuppressWarnings("unchecked")
    void zeroBreakHeaderLoadsWholePlanInOwnedWorkerAndChangesLocalAdviceResults(
            String header, boolean rawEnabled, String expectedMemoryProfile) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("main/resources/plans/zero_break.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        String key = "extremeZ.enabled";
        var originalKnobs = original.path("plan").path("overrides").path("knobs");
        assertTrue(originalKnobs.path(key).asBoolean());
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("plan").path("overrides").path("knobs"))
                .put(key, rawEnabled);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("plan").path("overrides").path("knobs"))
                .set(key, originalKnobs.path(key));
        assertEquals(original, restored, "only the nested enable value changes in the complete raw plan");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/zero_break.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "zero_break.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        WebSearchProvider web = mock(WebSearchProvider.class);
        ChatRunRegistry registry = spy(new ChatRunRegistry());
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        var done = new CountDownLatch(1);
        var workerCleared = new java.util.concurrent.atomic.AtomicBoolean();
        var doneTransitions = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            boolean marked = (Boolean) invocation.callRealMethod();
            if (marked) {
                doneTransitions.incrementAndGet();
                workerCleared.set(GuardContextHolder.get() == null);
                done.countDown();
            }
            return marked;
        }).when(registry).markDone(any(ChatRunExecutionContext.class));
        ChatApiController controller = controller(historyService, chatService, settingsService, ownerKeyResolver,
                registry, web);
        ReflectionTestUtils.setField(controller, "planHintApplier", applier);
        ReflectionTestUtils.setField(controller, "workflowOrchestrator",
                new com.example.lms.orchestration.WorkflowOrchestrator(applier));
        ChatSession session = new ChatSession("local request plan fixture", "owner-a", "ANON");
        session.setId(307L);
        when(settingsService.getAllSettings()).thenReturn(Map.of());
        when(ownerKeyResolver.ownerKey()).thenReturn("owner-a");
        when(historyService.startNewSession(any(), any(), any(), any(), any())).thenReturn(Optional.of(session));
        var calls = new AtomicInteger();
        var resultCount = new AtomicInteger();
        var callbackFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread caller = Thread.currentThread();
        when(chatService.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
            try {
                assertFalse(caller == Thread.currentThread(), "observe the real controller worker");
                var ctx = GuardContextHolder.get();
                assertNotNull(ctx);
                assertEquals(header, ctx.getPlanId());
                assertEquals(header, com.example.lms.search.TraceStore.get("plan.id.preSearch"));
                assertEquals("zero_break.v1", com.example.lms.search.TraceStore.get("plan.id"));
                assertEquals(expectedMemoryProfile, ctx.getMemoryProfile());
                assertEquals(Boolean.valueOf(rawEnabled), ctx.getPlanOverride(key));
                assertEquals(3, ctx.planInt("expand.selfAsk.count", -1));
                assertEquals(18, ctx.planInt("expand.queryBurst.count", -1));
                assertTrue(ctx.planBool("overdrive.enabled", false));
                assertNull(ctx.getPlanOverride("executionPlan.primaryMode"));
                assertNull(ctx.getPlanOverride("routing.executionPlan.primaryMode"));
                assertNull(com.example.lms.search.TraceStore.get("orch.pipeline.executed"));
                assertNull(com.example.lms.search.TraceStore.get("extremez.execute.activated"));
                assertNull(com.example.lms.guard.rulebreak.RuleBreakContextHolder.get(),
                        "direct controller invocation does not execute an MVC interceptor");
                assertNull(ctx.getPlanOverride("domainWhitelist.override"));
                assertFalse(ctx.isBypassMode(), "an observed flag is not full guard authorization proof");
                var auxiliary = new java.util.ArrayList<dev.langchain4j.rag.content.Content>();
                var queries = new java.util.ArrayList<String>();
                var retriever = new com.example.lms.service.rag.AnalyzeWebSearchRetriever(null, null, null, null, null, null) {
                    @Override public List<dev.langchain4j.rag.content.Content> retrieve(dev.langchain4j.rag.query.Query q) {
                        assertSame(ctx, GuardContextHolder.get());
                        queries.add(q.text());
                        var content = dev.langchain4j.rag.content.Content.from("local request evidence " + calls.incrementAndGet());
                        auxiliary.add(content);
                        return List.of(content);
                    }
                };
                org.springframework.beans.factory.ObjectProvider<com.example.lms.service.rag.AnalyzeWebSearchRetriever> provider =
                        mock(org.springframework.beans.factory.ObjectProvider.class);
                when(provider.getIfAvailable()).thenReturn(retriever);
                var props = new ai.abandonware.nova.config.NovaOrchestrationProperties();
                props.getExtremeZ().setEnabled(false);
                var aspect = new ai.abandonware.nova.orch.aop.ExtremeZBurstAspect(provider,
                        new ai.abandonware.nova.orch.anchor.AnchorNarrower(), props, null, null);
                var base = List.of(dev.langchain4j.rag.content.Content.from("local request base evidence"));
                var pjp = mock(org.aspectj.lang.ProceedingJoinPoint.class);
                when(pjp.getArgs()).thenReturn(new Object[]{com.example.lms.service.rag.QueryUtils.buildQuery(
                        "machine learning optimization benchmark", Map.of())});
                when(pjp.proceed()).thenReturn(base);
                // Controlled callback-to-advice invocation; this does not exercise the real ChatService retrieval wiring.
                var result = (List<dev.langchain4j.rag.content.Content>) aspect.aroundHybridRetrieve(pjp);
                assertEquals(rawEnabled, calls.get() > 0);
                assertTrue(calls.get() <= 18, "retained query-burst count is an upper bound");
                assertEquals(calls.get(), queries.stream().distinct().count());
                var expected = new java.util.ArrayList<>(base);
                expected.addAll(auxiliary);
                assertEquals(expected, result);
                assertSame(base.get(0), result.get(0));
                if (!rawEnabled) assertSame(base, result);
                assertEquals(rawEnabled ? "sparse" : "disabled",
                        com.example.lms.search.TraceStore.get("extremez.activation.reason"));
                assertEquals(Boolean.valueOf(rawEnabled), com.example.lms.search.TraceStore.get("extremez.activated"));
                verify(pjp, times(1)).proceed();
                resultCount.set(result.size());
                return ChatResult.of("local evidence count: " + result.size(), "fixture-model", false);
            } catch (Throwable failure) {
                callbackFailure.set(failure);
                throw failure;
            }
        });
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Jammini-Mode", header);
        GuardContextHolder.clear();
        com.example.lms.search.TraceStore.clear();
        com.example.lms.guard.rulebreak.RuleBreakContextHolder.clear();
        try {
            var events = controller.chatStream(ChatRequestDto.builder().message("local request plan fixture")
                    .useWebSearch(false).useRag(false).build(), false, false, null, request)
                    .collectList().block(Duration.ofSeconds(10));
            assertNull(callbackFailure.get(), () -> String.valueOf(callbackFailure.get()));
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(workerCleared.get());
            assertFalse(registry.isRunning(307L));
            assertEquals(1, doneTransitions.get(), "repeated completion callbacks must transition the owned run once");
            verify(registry, org.mockito.Mockito.atLeastOnce()).markDone(any(ChatRunExecutionContext.class));
            verify(chatService, times(1)).continueChat(any(ChatRequestDto.class), any());
            verifyNoInteractions(web);
            assertNotNull(events);
            assertTrue(events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())
                    && ("local evidence count: " + resultCount.get()).equals(e.data().data())));
            assertEquals(1 + calls.get(), resultCount.get());
            System.out.printf("TBL07_ZERO_BREAK_REQUEST header=%s rawEnabled=%s memoryProfile=%s calls=%d results=%d ruleBreakContext=absent workerCleared=true doneTransitions=1 ownedRunDone=true externalRequests=0%n",
                    header, rawEnabled, expectedMemoryProfile, calls.get(), resultCount.get());
        } finally {
            GuardContextHolder.clear();
            com.example.lms.search.TraceStore.clear();
            com.example.lms.guard.rulebreak.RuleBreakContextHolder.clear();
        }
    }
    private static void stubOwnerRun(ChatRunRegistry runRegistry, long sessionId) {
        ChatRunRegistry backing = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(backing, "replayCapacity", 32);
        org.springframework.test.util.ReflectionTestUtils.setField(backing, "ttlSeconds", 60);
        when(runRegistry.beginOrJoin(sessionId)).thenReturn(backing.beginOrJoin(sessionId));
    }

    private static ChatApiController controller(
            ChatHistoryService historyService,
            ChatService chatService,
            SettingsService settingsService,
            ClientOwnerKeyResolver ownerKeyResolver) {
        ChatRunRegistry registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        return controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                registry,
                null);
    }

    private static ChatApiController controller(
            ChatHistoryService historyService,
            ChatService chatService,
            SettingsService settingsService,
            ClientOwnerKeyResolver ownerKeyResolver,
            ChatRunRegistry runRegistry) {
        return controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                null,
                null);
    }

    private static ChatApiController controller(
            ChatHistoryService historyService,
            ChatService chatService,
            SettingsService settingsService,
            ClientOwnerKeyResolver ownerKeyResolver,
            ChatRunRegistry runRegistry,
            WebSearchProvider webSearchProvider) {
        return controller(
                historyService,
                chatService,
                settingsService,
                ownerKeyResolver,
                runRegistry,
                webSearchProvider,
                null);
    }

    private static ChatApiController controller(
            ChatHistoryService historyService,
            ChatService chatService,
            SettingsService settingsService,
            ClientOwnerKeyResolver ownerKeyResolver,
            ChatRunRegistry runRegistry,
            WebSearchProvider webSearchProvider,
            ChatStreamEmitter chatStreamEmitter) {
        if (org.mockito.Mockito.mockingDetails(runRegistry).isMock()
                && !org.mockito.Mockito.mockingDetails(runRegistry).isSpy()) {
            when(runRegistry.interactiveClient()).thenAnswer(ignored -> mock(ChatRunRegistry.InteractiveClient.class));
            when(runRegistry.interactiveSource(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        }
        return new ChatApiController(
                historyService,
                chatService,
                null,
                settingsService,
                null,
                webSearchProvider,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                chatStreamEmitter,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                null,
                runRegistry,
                ownerKeyResolver);
    }
}
