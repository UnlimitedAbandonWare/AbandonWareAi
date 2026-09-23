package com.example.lms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyFactory;
import com.example.lms.infra.selection.SelectionReplaySpec;
import com.example.lms.service.AdaptiveTranslationService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.service.chat.ChatStreamEmitter;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

class ChatApiControllerSelectionReplayTest {

    @AfterEach
    void clearGuardContext() {
        GuardContextHolder.clear();
    }

    @Test
    void resolverRunsBeforeAsyncAndStreamFanOutWithoutHeaderCapture() throws Exception {
        String controllerSource = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8);

        assertThat(controllerSource).contains("selectionReplayRequestResolver.resolve(request)");
        assertThat(controllerSource.indexOf("selectionReplayRequestResolver.resolve(request)"))
                .isLessThan(controllerSource.indexOf("ContextPropagation.wrapCallable"));
        int chatEntry = controllerSource.indexOf("public Mono<ResponseEntity<ChatResponseDto>> chat(");
        int chatResolve = controllerSource.indexOf("resolveSelectionReplayRequest(request)", chatEntry);
        int chatBudget = controllerSource.indexOf("publicRequestBudgetGuard.validateChat(req)", chatEntry);
        assertThat(chatResolve).isGreaterThan(chatEntry).isLessThan(chatBudget);
        int streamEntry = controllerSource.indexOf("public Flux<ServerSentEvent<ChatStreamEvent>> chatStream(");
        int streamResolve = controllerSource.indexOf("resolveSelectionReplayRequest(request)", streamEntry);
        int streamBudget = controllerSource.indexOf(
                "publicRequestBudgetGuard.validateChatForStream(req, attach)", streamEntry);
        assertThat(streamResolve).isGreaterThan(streamEntry).isLessThan(streamBudget);
        assertThat(controllerSource).containsPattern("attachSelectionState\\s*\\(\\s*ctx\\s*,");
        assertThat(controllerSource).containsPattern("attachSelectionState\\s*\\(\\s*gctx\\s*,");
        assertThat(controllerSource).doesNotContain("X-AWX-Selection-Replay\", req");
        assertThat(controllerSource).doesNotContain("setSelectionReplay");
    }

    @Test
    void rejectedChatRequestStopsBeforeEveryDownstreamDependency() {
        ControllerFixture fixture = fixture();
        MockHttpServletRequest request = new MockHttpServletRequest();
        SelectionReplayRequestException forbidden = SelectionReplayRequestException.forbidden();
        when(fixture.resolver().resolve(request)).thenThrow(forbidden);

        assertThatThrownBy(() -> fixture.controller().chat(chatRequest(), null, request))
                .isSameAs(forbidden);

        verify(fixture.resolver()).resolve(request);
        fixture.verifyNoDownstreamInteractions();
    }

    @Test
    void rejectedStreamRequestStopsBeforeEveryDownstreamDependency() {
        ControllerFixture fixture = fixture();
        MockHttpServletRequest request = new MockHttpServletRequest();
        SelectionReplayRequestException invalid = SelectionReplayRequestException.invalid();
        when(fixture.resolver().resolve(request)).thenThrow(invalid);

        assertThatThrownBy(() -> fixture.controller().chatStream(
                        chatRequest(), false, false, null, request))
                .isSameAs(invalid);

        verify(fixture.resolver()).resolve(request);
        fixture.verifyNoDownstreamInteractions();
    }

    @Test
    void acceptedChatCarriesExactReferencesIntoCreatedContext() {
        ControllerFixture fixture = fixture();
        MockHttpServletRequest request = new MockHttpServletRequest();
        SelectionEntropy entropy =
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32]));
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        when(fixture.resolver().resolve(request))
                .thenReturn(new SelectionReplayRequestResolver.Resolved(entropy, ledger));
        AtomicReference<GuardContext> observed = captureContextFromSettings(fixture);

        fixture.controller().chat(chatRequest(), null, request).block(Duration.ofSeconds(5));

        assertExactSelectionState(observed.get(), entropy, ledger);
    }

    @Test
    void acceptedStreamCarriesExactReferencesIntoCreatedWorkerContext() {
        ControllerFixture fixture = fixture();
        MockHttpServletRequest request = new MockHttpServletRequest();
        SelectionEntropy entropy =
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32]));
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        when(fixture.resolver().resolve(request))
                .thenReturn(new SelectionReplayRequestResolver.Resolved(entropy, ledger));
        AtomicReference<GuardContext> observed = captureContextFromSettings(fixture);

        fixture.controller().chatStream(chatRequest(), false, false, null, request)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertExactSelectionState(observed.get(), entropy, ledger);
    }

    @Test
    void attachHelperPreservesTheExactAcceptedReferences() {
        GuardContext context = GuardContext.defaultContext();
        SelectionEntropy entropy =
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32]));
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();

        ReflectionTestUtils.invokeMethod(
                ChatApiController.class,
                "attachSelectionState",
                context,
                entropy,
                ledger);

        assertThat(context.selectionEntropy()).isSameAs(entropy);
        assertThat(context.selectionDecisionLedger()).isSameAs(ledger);
    }

    @Test
    void exceptionHandlerProducesExactlyOneFixedJsonProperty() throws Exception {
        SelectionReplayExceptionHandler handler = new SelectionReplayExceptionHandler();
        var response = handler.handle(SelectionReplayRequestException.forbidden());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(new ObjectMapper().writeValueAsString(response.getBody()))
                .isEqualTo("{\"code\":\"selection_entropy_replay_forbidden\"}");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void planApplicationCountsRespectControllerBudgetAndWorkerContexts(boolean stream) {
        ControllerFixture fixture = fixture();
        var applier = org.mockito.Mockito.spy(new com.example.lms.plan.PlanHintApplier(
                new org.springframework.core.io.DefaultResourceLoader()));
        var plan = new com.example.lms.plan.PlanHints("rc04-fixture", null, null, java.util.List.of(),
                4, 6, 2, java.util.List.of(), 500L, 600L, 2, false, false, false,
                false, false, "embedding-model", 2, 4, null, null, Map.of());
        org.mockito.Mockito.doReturn(plan).when(applier).load("rc04-fixture");
        var selector = org.mockito.Mockito.spy(new com.example.lms.orchestration.WorkflowOrchestrator(applier));
        ReflectionTestUtils.setField(fixture.controller(), "planHintApplier", applier);
        ReflectionTestUtils.setField(fixture.controller(), "workflowOrchestrator", selector);
        ReflectionTestUtils.setField(fixture.controller(), "chatStreamEmitter", new ChatStreamEmitter());
        var session = new com.example.lms.domain.ChatSession("plan application probe", "selection-test-owner", "ANON");
        session.setId(7901L);
        when(fixture.settingsService().getAllSettings()).thenReturn(Map.of());
        when(fixture.historyService().startNewSession(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(session));
        var backingRuns = new ChatRunRegistry();
        ReflectionTestUtils.setField(backingRuns, "replayCapacity", 32);
        ReflectionTestUtils.setField(backingRuns, "ttlSeconds", 60);
        when(fixture.runRegistry().beginOrJoin(7901L)).thenReturn(backingRuns.beginOrJoin(7901L));
        when(fixture.historyService().appendMessageReturningId(7901L, "assistant", "plan probe answer"))
                .thenReturn(7902L);
        AtomicReference<GuardContext> delegatedContext = new AtomicReference<>();
        AtomicReference<ChatRequestDto> delegatedRequest = new AtomicReference<>();
        when(fixture.chatService().continueChat(org.mockito.ArgumentMatchers.any(ChatRequestDto.class),
                org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                    delegatedContext.set(GuardContextHolder.get());
                    delegatedRequest.set(call.getArgument(0));
                    return com.example.lms.service.ChatResult.of("plan probe answer", "fake-model", false);
                });
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(fixture.resolver().resolve(request)).thenReturn(new SelectionReplayRequestResolver.Resolved(
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32])),
                SelectionDecisionLedger.forReplay()));
        request.addHeader("X-Jammini-Mode", "rc04-fixture");
        ChatRequestDto dto = ChatRequestDto.builder().message("plan application probe")
                .useWebSearch(true).useRag(true).memoryMode("EPHEMERAL").build();
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        try {
            if (stream) {
                var events = fixture.controller().chatStream(dto, false, false, null, request)
                        .collectList().block(Duration.ofSeconds(5));
                assertThat(events).isNotNull().anySatisfy(event -> {
                    assertThat(event.data()).isNotNull();
                    assertThat(event.data().type()).isEqualTo("final");
                    assertThat(event.data().data()).isEqualTo("plan probe answer");
                });
            } else {
                var response = fixture.controller().chat(dto, null, request).block(Duration.ofSeconds(5));
                assertThat(response).isNotNull();
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            }
            int expectedApplications = stream ? 2 : 1;
            verify(applier, org.mockito.Mockito.times(expectedApplications)).load("rc04-fixture");
            var contexts = org.mockito.ArgumentCaptor.forClass(GuardContext.class);
            verify(applier, org.mockito.Mockito.times(expectedApplications))
                    .applyToGuardContext(org.mockito.ArgumentMatchers.same(plan), contexts.capture());
            verify(applier, org.mockito.Mockito.never()).applyToHintsAndMeta(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            verify(selector, org.mockito.Mockito.times(expectedApplications)).ensurePlanSelected(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyBoolean());
            verify(fixture.chatService()).continueChat(org.mockito.ArgumentMatchers.any(ChatRequestDto.class),
                    org.mockito.ArgumentMatchers.any());
            assertThat(delegatedRequest.get()).isNotNull();
            assertThat(delegatedRequest.get().isUseWebSearch()).isFalse();
            assertThat(delegatedRequest.get().isUseRag()).isFalse();
            assertThat(contexts.getAllValues()).allSatisfy(context -> {
                assertThat(context.getPlanOverride("onnx.enabled")).isEqualTo(false);
                assertThat(context.getPlanOverride("rerank.topK")).isEqualTo(2);
                assertThat(context.getMinCitations()).isEqualTo(2);
            });
            assertThat(contexts.getAllValues().get(expectedApplications - 1)).isSameAs(delegatedContext.get());
            if (stream) assertThat(contexts.getAllValues().get(0)).isNotSameAs(delegatedContext.get());
        } finally {
            GuardContextHolder.clear();
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
            com.example.lms.search.TraceStore.clear();
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    private static ChatRequestDto chatRequest() {
        return ChatRequestDto.builder()
                .message("fixed selection replay test")
                .useRag(false)
                .useWebSearch(false)
                .build();
    }

    private static AtomicReference<GuardContext> captureContextFromSettings(
            ControllerFixture fixture) {
        AtomicReference<GuardContext> observed = new AtomicReference<>();
        when(fixture.settingsService().getAllSettings()).thenAnswer(ignored -> {
            GuardContext current = GuardContextHolder.get();
            if (current != null) {
                observed.compareAndSet(null, current);
            }
            return Map.<String, String>of();
        });
        return observed;
    }

    private static void assertExactSelectionState(
            GuardContext observed,
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger) {
        assertThat(observed).isNotNull();
        assertThat(observed.selectionEntropy()).isSameAs(entropy);
        assertThat(observed.selectionDecisionLedger()).isSameAs(ledger);
    }

    private static ControllerFixture fixture() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatService chatService = mock(ChatService.class);
        AdaptiveTranslationService adaptiveService = mock(AdaptiveTranslationService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ChatRunRegistry runRegistry = mock(ChatRunRegistry.class);
        when(runRegistry.interactiveClient()).thenReturn(mock(ChatRunRegistry.InteractiveClient.class));
        when(runRegistry.interactiveSource(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        ChatStreamEmitter streamEmitter = mock(ChatStreamEmitter.class);
        ClientOwnerKeyResolver ownerKeyResolver = mock(ClientOwnerKeyResolver.class);
        SelectionReplayRequestResolver resolver = mock(SelectionReplayRequestResolver.class);
        when(ownerKeyResolver.ownerKey()).thenReturn("selection-test-owner");
        ChatApiController controller = new ChatApiController(
                historyService,
                chatService,
                adaptiveService,
                settingsService,
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
                streamEmitter,
                new ObjectMapper(),
                null,
                runRegistry,
                ownerKeyResolver);
        ReflectionTestUtils.setField(controller, "selectionReplayRequestResolver", resolver);
        return new ControllerFixture(
                controller,
                resolver,
                historyService,
                chatService,
                adaptiveService,
                settingsService,
                runRegistry,
                streamEmitter,
                ownerKeyResolver);
    }

    private record ControllerFixture(
            ChatApiController controller,
            SelectionReplayRequestResolver resolver,
            ChatHistoryService historyService,
            ChatService chatService,
            AdaptiveTranslationService adaptiveService,
            SettingsService settingsService,
            ChatRunRegistry runRegistry,
            ChatStreamEmitter streamEmitter,
            ClientOwnerKeyResolver ownerKeyResolver) {

        void verifyNoDownstreamInteractions() {
            verifyNoInteractions(
                    historyService,
                    chatService,
                    adaptiveService,
                    settingsService,
                    runRegistry,
                    streamEmitter,
                    ownerKeyResolver);
        }
    }
}
