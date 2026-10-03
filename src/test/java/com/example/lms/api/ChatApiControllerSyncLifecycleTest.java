package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatApiControllerSyncLifecycleTest {

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "failure,FAIL_SOFT", "empty,OK", "no-provider,SKIPPED", "off,SKIPPED"
    })
    void agentSearchOutcomeSurvivesOrdinaryChatResponseAndStreamProjection(String scenario, String status) {
        try (Fixture f = new Fixture()) {
            var calls = new AtomicInteger();
            var tools = new com.abandonware.ai.agent.tool.ToolRegistry();
            tools.register(new com.abandonware.ai.agent.tool.impl.WebSearchTool((q, k, locale) -> {
                calls.incrementAndGet();
                if ("failure".equals(scenario)) throw new IllegalStateException("private synthetic detail");
                com.example.lms.search.TraceStore.put("agent.acmeGateway.result.reason",
                        "no-provider".equals(scenario) ? "no-eligible-provider" : "zero-result");
                return List.of();
            }));
            var catalog = new com.abandonware.ai.agent.contract.ToolManifestCatalog(
                    new org.springframework.mock.env.MockEnvironment()
                            .withProperty("agent.tools.web-search.enabled", "true"), () -> tools);
            var invoker = new com.abandonware.ai.agent.tool.AgentToolInvoker(tools, catalog,
                    new com.abandonware.ai.agent.policy.ToolPolicyEnforcer(), null, null,
                    new com.abandonware.ai.agent.tool.AgentToolArtifactWriter());
            ReflectionTestUtils.setField(f.controller, "agentToolInvoker", invoker);
            ReflectionTestUtils.setField(f.controller, "agentWebSearchEnabled", true);
            var rollout = new com.example.lms.orchestration.control.RagControlRolloutState(
                    new com.example.lms.orchestration.control.RagControlProperties(20, 0.01d, 20));
            ReflectionTestUtils.setField(f.controller, "ragControlPresentationBoundary",
                    new com.example.lms.orchestration.control.RagControlPresentationBoundary(
                            new com.example.lms.orchestration.control.RagControlCoordinator(
                                    new com.example.lms.orchestration.control.RagGuardProbeComposer(), rollout),
                            new com.example.lms.orchestration.control.RagControlRuntimeAdapter(),
                            new com.example.lms.orchestration.control.RagControlProjectionRenderer(), null));
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            "synthetic-operator", null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
            var captured = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                var supplier = (com.example.lms.service.ChatWorkflow.WebEvidenceSupplier) invocation.getArgument(1);
                assertTrue(supplier.evidence("synthetic search").isEmpty());
                com.example.lms.orchestration.control.RagControlRuntimeAdapter.capturePresentationInput(
                        com.example.lms.orchestration.control.RagControlRuntimeAdapter.RuntimeInput.evidenceNeeded(true));
                captured.set(com.example.lms.search.TraceStore.getAll());
                return ChatResult.of("generated answer", "mock-model", false);
            });
            boolean enabled = !"off".equals(scenario);
            var request = ChatRequestDto.builder().message("synthetic search question").sessionId(42L)
                    .searchMode(enabled ? com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT
                            : com.example.lms.gptsearch.dto.SearchMode.OFF)
                    .useRag(enabled).useWebSearch(enabled).build();
            var response = f.controller.chatSync(request, null, new MockHttpServletRequest());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("generated answer", response.getBody().getContent());
            assertEquals(enabled ? 1 : 0, calls.get());
            assertEquals(status, captured.get().get("agent.webSearch.prompt.status"));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var syncJson = mapper.valueToTree(response.getBody());
            var outcome = syncJson.path("pipelineSnapshot").path("agentWebSearch");
            assertEquals(status, outcome.path("status").asText());
            assertEquals(0, outcome.path("returnedCount").asInt(-1));
            assertEquals(java.util.Objects.toString(captured.get().get("agent.webSearch.prompt.reasonCode"), ""),
                    outcome.path("reasonCode").asText(""));
            var pipeline = ChatStreamSignalBuilder.buildPipelineSnapshot(captured.get(), null, null, null);
            var streamJson = mapper.valueToTree(com.example.lms.dto.ChatStreamEvent.trace(null, null,
                    ChatStreamSignalBuilder.withTraceTurnId(pipeline, null, 421L)));
            assertEquals(outcome, streamJson.path("pipelineSnapshot").path("agentWebSearch"));
            assertFalse(syncJson.toString().contains("private synthetic detail"));
            assertFalse(streamJson.toString().contains("private synthetic detail"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            com.example.lms.search.TraceStore.clear();
            com.example.lms.trace.TraceContext.cleanupCurrentThread();
        }
    }

    @org.junit.jupiter.api.Test
    void absentSearchObservationDoesNotReusePreviousOutcome() {
        var failed = ChatStreamSignalBuilder.buildPipelineSnapshot(Map.of(
                "agent.webSearch.prompt.status", "FAIL_SOFT",
                "agent.webSearch.prompt.reasonCode", "web_search_failed",
                "agent.webSearch.prompt.returnedCount", 0), null, null, null);
        assertNotNull(failed, "an observed search outcome must survive an otherwise empty snapshot");
        assertNull(ChatStreamSignalBuilder.buildPipelineSnapshot(Map.of(), null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "chat"})
    void incompleteResponseReachesEnvelopeWithoutCompletedPersistence(String route) throws Exception {
        try (Fixture f = new Fixture()) {
            var metadata = dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                    .id("resp_fixture").modelName("fixture-model")
                    .tokenUsage(new dev.langchain4j.model.output.TokenUsage(3, 2, 5))
                    .finishReason(dev.langchain4j.model.output.FinishReason.LENGTH).build();
            var terminal = new com.example.lms.llm.gateway.LlmResponseTerminalException(
                    "output_limit_reached", com.example.lms.llm.gateway.LlmFailureClass.NONE,
                    "partial text", metadata, "incomplete", "max_output_tokens", null);
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenThrow(terminal);
            var result = f.request(route);
            assertEquals(HttpStatus.OK, result.getStatusCode());
            assertEquals("partial text", result.getBody().getContent());
            assertEquals(42L, result.getBody().getSessionId());
            var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result.getBody());
            assertEquals("LENGTH", json.path("generationTermination").path("finishReason").asText());
            assertEquals("output_limit_reached", json.path("generationTermination").path("reason").asText());
            assertEquals(5, json.path("generationTermination").path("totalTokens").asInt());
            assertEquals("resp_fixture", json.path("generationTermination").path("responseId").asText());
            verify(f.chat, times(1)).continueChat(any(ChatRequestDto.class), any());
            verify(f.history, never()).appendMessageReturningId(anyLong(), eq("assistant"), anyString());
            assertFalse(f.registry.isRunning(42L));
        }
    }

    @org.junit.jupiter.api.Test
    void consecutiveSyncResponsesKeepSessionHeaderBodyAndPersistenceTargetAligned() {
        try (Fixture f = new Fixture()) {
            String firstQuestion = "물은 수소와 산소로 이루어져 있나요?";
            String nextQuestion = "그 원자 수의 비율은 무엇인가요?";
            var first = f.controller.chatSync(ChatRequestDto.builder()
                    .message(firstQuestion).sessionId(42L).useRag(false).useWebSearch(false).build(), null,
                    new MockHttpServletRequest());
            assertEquals(HttpStatus.OK, first.getStatusCode());
            assertNotNull(first.getBody());
            var second = f.controller.chatSync(ChatRequestDto.builder()
                    .message(nextQuestion).sessionId(first.getBody().getSessionId())
                    .useRag(false).useWebSearch(false).build(), null,
                    new MockHttpServletRequest());
            assertEquals(HttpStatus.OK, second.getStatusCode());
            assertNotNull(second.getBody());
            assertEquals("42", first.getHeaders().getFirst("X-Session-Id"));
            assertEquals(first.getHeaders().getFirst("X-Session-Id"), second.getHeaders().getFirst("X-Session-Id"));
            assertEquals(first.getBody().getSessionId(), second.getBody().getSessionId());
            assertEquals(second.getHeaders().getFirst("X-Session-Id"), String.valueOf(second.getBody().getSessionId()));
            verify(f.history).appendMessageReturningId(42L, "user", firstQuestion);
            verify(f.history).appendMessageReturningId(42L, "user", nextQuestion);
            verify(f.history, never()).appendMessage(eq(42L), eq("user"), anyString());
            verify(f.history, times(2)).appendMessageReturningId(42L, "assistant", "generated answer");
            verify(f.chat, times(2)).continueChat(any(ChatRequestDto.class), any());
        }
    }

    @org.junit.jupiter.api.Test
    void foreignSessionCannotGainContinuityThroughASyncResponseHeader() {
        try (Fixture f = new Fixture()) {
            var foreign = new ChatSession("synthetic", "different-owner", "ANON");
            foreign.setId(42L);
            when(f.history.getSessionWithMessages(42L)).thenReturn(foreign);
            when(f.history.getSessionWithMessages(42L, 1)).thenReturn(foreign);
            var denied = f.request("sync");
            assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
            assertNull(denied.getHeaders().getFirst("X-Session-Id"));
            verifyNoInteractions(f.chat);
            verify(f.history, never()).appendMessage(anyLong(), anyString(), anyString());
            verify(f.history, never()).appendMessageReturningId(anyLong(), anyString(), anyString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "chat"})
    void deletionDuringGenerationRejectsLatePersistenceAndSuccessfulAnswer(String route) throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch generating = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                generating.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return ChatResult.of("generated answer", "mock-model", false);
            });
            Future<ResponseEntity<ChatResponseDto>> response = f.executor.submit(() -> f.request(route));
            try {
                assertTrue(generating.await(5, TimeUnit.SECONDS));
                assertEquals(HttpStatus.NO_CONTENT, f.controller.deleteSession(42L, null).getStatusCode());
            } finally {
                release.countDown();
            }
            ResponseEntity<ChatResponseDto> result = response.get(5, TimeUnit.SECONDS);
            assertEquals(0, f.appendAfterDelete.get(), "no assistant append may start after deletion completed");
            assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
            assertNotEquals("generated answer", result.getBody().getContent());
            assertFalse(f.registry.isRunning(42L));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "chat"})
    void nonStreamingRunBindsGenerationAndReleasesOwnershipAfterSuccess(String route) {
        try (Fixture f = new Fixture()) {
            AtomicBoolean bound = new AtomicBoolean();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                ChatRunExecutionContext context = ChatRunExecutionContext.current();
                bound.set(context != null && context.belongsToSession(42L) && f.registry.isRunning(42L));
                return ChatResult.of("generated answer", "mock-model", false);
            });
            assertEquals(HttpStatus.OK, f.request(route).getStatusCode());
            assertTrue(bound.get(), "workflow must receive the exact registered execution capability");
            assertFalse(f.registry.isRunning(42L));
            assertNull(ChatRunExecutionContext.current());
            verify(f.history).appendMessageReturningId(42L, "assistant", "generated answer");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "chat"})
    void existingRunPreventsConcurrentNonStreamingGenerationWithoutEndingItsOwner(String route) {
        try (Fixture f = new Fixture()) {
            var existing = f.registry.beginOrJoin(42L);
            assertTrue(existing.owner());
            var result = f.request(route);
            assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
            verifyNoInteractions(f.chat);
            verify(f.history, never()).appendMessageReturningId(anyLong(), anyString(), anyString());
            verify(f.history, never()).appendMessage(42L, "user", "ordinary question");
            assertTrue(f.registry.isRunning(42L));
            assertFalse(existing.context().isCancellationRequested());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "chat"})
    void deletionWaitsForAnAlreadyAdmittedNonStreamingDurableBlock(String route) throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch persisting = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch deletionEntered = new CountDownLatch(1);
            doAnswer(invocation -> {
                deletionEntered.countDown();
                return invocation.callRealMethod();
            }).when(f.registry).cancelSessionForDeletion(42L);
            when(f.history.appendMessageReturningId(42L, "assistant", "generated answer")).thenAnswer(invocation -> {
                f.events.add("assistant-start");
                persisting.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                f.events.add("assistant-end");
                return 421L;
            });
            Future<ResponseEntity<ChatResponseDto>> response = f.executor.submit(() -> f.request(route));
            Future<ResponseEntity<?>> deletion = null;
            try {
                assertTrue(persisting.await(5, TimeUnit.SECONDS));
                deletion = f.executor.submit(() -> f.controller.deleteSession(42L, null));
                assertTrue(deletionEntered.await(5, TimeUnit.SECONDS));
                Future<ResponseEntity<?>> pending = deletion;
                assertThrows(TimeoutException.class, () -> pending.get(150, TimeUnit.MILLISECONDS),
                        "deletion must wait for the admitted durable block");
            } finally {
                release.countDown();
            }
            assertEquals(HttpStatus.OK, response.get(5, TimeUnit.SECONDS).getStatusCode());
            assertEquals(HttpStatus.NO_CONTENT, deletion.get(5, TimeUnit.SECONDS).getStatusCode());
            assertTrue(f.events.indexOf("assistant-end") < f.events.indexOf("delete-history"));
            assertFalse(f.registry.isRunning(42L));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "chat"})
    void failedGenerationReleasesExactRunAndThreadBinding(String route) {
        try (Fixture f = new Fixture()) {
            AtomicBoolean bound = new AtomicBoolean();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                bound.set(ChatRunExecutionContext.current() != null);
                throw new IllegalStateException("synthetic-generation-failure");
            });
            if ("sync".equals(route)) assertThrows(IllegalStateException.class, () -> f.request(route));
            else assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, f.request(route).getStatusCode());
            assertTrue(bound.get());
            assertFalse(f.registry.isRunning(42L));
            assertNull(ChatRunExecutionContext.current());
            verify(f.history).appendMessageReturningId(eq(42L), eq("user"), anyString());
            verify(f.history, never()).appendMessageReturningId(anyLong(), eq("assistant"), anyString());
        }
    }

    @org.junit.jupiter.api.Test
    void blockedStreamEmitsTerminalMetadataWithoutInventingAnswer() {
        try (Fixture f = new Fixture()) {
            var metadata = dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                    .modelName("fixture-model").finishReason(dev.langchain4j.model.output.FinishReason.CONTENT_FILTER)
                    .tokenUsage(new dev.langchain4j.model.output.TokenUsage(3, 2, 5)).build();
            var terminal = new com.example.lms.llm.gateway.LlmResponseTerminalException(
                    "content_filter", com.example.lms.llm.gateway.LlmFailureClass.NONE,
                    null, metadata, "incomplete", "content_filter", null);
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenThrow(terminal);
            var req = ChatRequestDto.builder().message("ordinary question").sessionId(42L)
                    .useRag(false).useWebSearch(false).build();
            var events = f.controller.chatStream(req, false, false, null, new MockHttpServletRequest())
                    .collectList().block(Duration.ofSeconds(5));
            var finalEvent = events.stream().map(org.springframework.http.codec.ServerSentEvent::data)
                    .filter(e -> e != null && "final".equals(e.type())).findFirst().orElseThrow();
            assertEquals("", finalEvent.data());
            assertEquals("content_filter", finalEvent.generationTermination().reason());
            assertEquals(5, finalEvent.generationTermination().totalTokens());
            verify(f.history, never()).appendMessageReturningId(anyLong(), eq("assistant"), anyString());
            verify(f.chat, times(1)).continueChat(any(ChatRequestDto.class), any());
            assertFalse(f.registry.isRunning(42L));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "chat"})
    void requestSettingsUseMetadataLookupWithoutTranscriptLoad(String route) {
        try (Fixture f = new Fixture()) {
            var response = f.request(route);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("generated answer", response.getBody().getContent());
            verify(f.history, atLeastOnce()).getSessionForRequest(42L);
            verify(f.history, never()).getSessionWithMessages(42L);
        }
    }


    private static final class Fixture implements AutoCloseable {
        final ChatHistoryService history = mock(ChatHistoryService.class);
        final ChatService chat = mock(ChatService.class);
        final ChatRunRegistry registry = spy(new ChatRunRegistry());
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final AtomicBoolean deleted = new AtomicBoolean();
        final AtomicInteger appendAfterDelete = new AtomicInteger();
        final List<String> events = new CopyOnWriteArrayList<>();
        final ChatApiController controller;

        Fixture() {
            ReflectionTestUtils.setField(registry, "replayCapacity", 32);
            ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
            SettingsService settings = mock(SettingsService.class);
            ClientOwnerKeyResolver owners = mock(ClientOwnerKeyResolver.class);
            when(settings.getAllSettings()).thenReturn(Map.of());
            when(owners.ownerKey()).thenReturn("owner-a");
            controller = new ChatApiController(history, chat, null, settings, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    new com.fasterxml.jackson.databind.ObjectMapper(), null, registry, owners);
            ChatSession session = new ChatSession("lifecycle", "owner-a", "ANON");
            session.setId(42L);
            when(history.getSessionWithMessages(42L)).thenAnswer(invocation -> deleted.get() ? null : session);
            when(history.getSessionForRequest(42L)).thenAnswer(invocation -> deleted.get() ? null : session);
            when(history.getSessionWithMessages(42L, 1)).thenAnswer(invocation -> deleted.get() ? null : session);
            doAnswer(invocation -> {
                events.add("delete-history");
                deleted.set(true);
                return null;
            }).when(history).deleteSession(42L);
            when(history.appendMessageReturningId(42L, "assistant", "generated answer")).thenAnswer(invocation -> {
                if (deleted.get()) appendAfterDelete.incrementAndGet();
                return deleted.get() ? null : 421L;
            });
            when(chat.continueChat(any(ChatRequestDto.class), any()))
                    .thenReturn(ChatResult.of("generated answer", "mock-model", false));
        }

        ResponseEntity<ChatResponseDto> request(String route) {
            ChatRequestDto request = ChatRequestDto.builder().message("ordinary question")
                    .sessionId(42L).useRag(false).useWebSearch(false).build();
            return "sync".equals(route) ? controller.chatSync(request, null, new MockHttpServletRequest())
                    : controller.chat(request, null, new MockHttpServletRequest()).block(Duration.ofSeconds(5));
        }

        @Override public void close() {
            executor.shutdownNow();
            ReflectionTestUtils.invokeMethod(registry, "shutdown");
        }
    }
}
