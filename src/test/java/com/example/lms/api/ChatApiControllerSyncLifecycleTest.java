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

    @org.junit.jupiter.api.Test
    void cancelledOwnerDoesNotBlockOtherControllersOrReplayTheirResult(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:file:" + directory.resolve("admission") + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.FileSystemResource("main/resources/db/migration/V20260912_02__chat_requests.sql"),
                new org.springframework.core.io.FileSystemResource("main/resources/db/migration/V20260912_04__chat_request_results.sql"))
                .execute(dataSource);
        var redis = mock(com.example.lms.infra.upstash.UpstashRedisClient.class);
        when(redis.enabled()).thenReturn(true);
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(reactor.core.publisher.Mono.just(List.of(1L, 0L)));
        var guard = new PublicChatAdmissionGuard(2, 1, 4);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var run = new java.util.concurrent.atomic.AtomicReference<ChatRunExecutionContext>();
        var subscription = new java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable>();
        try (Fixture a = new Fixture(); Fixture b = new Fixture("owner-b", 43L, a.registry)) {
            ReflectionTestUtils.setField(a.controller, "publicChatAdmissionGuard", guard);
            ReflectionTestUtils.setField(b.controller, "publicChatAdmissionGuard", guard);
            var filterA = new ChatGenerationAdmissionFilter(redis, dataSource, a.owners);
            var filterB = new ChatGenerationAdmissionFilter(redis, dataSource, b.owners);
            when(a.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                run.set(ChatRunExecutionContext.current());
                entered.countDown();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (release.getCount() != 0) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) throw new IllegalStateException("synthetic worker timeout");
                        try { release.await(remaining, TimeUnit.NANOSECONDS); }
                        catch (InterruptedException ignored) { /* Non-cooperative transport returns late. */ }
                    }
                    return ChatResult.of("owner-a late answer", "mock-model", false);
                } finally {
                    exited.countDown();
                }
            });
            var requestA = a.admittedRequest("ordinary question", "shared-key");
            var responseA = new org.springframework.mock.web.MockHttpServletResponse();
            try {
                filterA.doFilter(requestA, responseA, (rq, rs) -> {
                    subscription.set(a.controller.chat(a.requestDto("ordinary question"), null,
                            (jakarta.servlet.http.HttpServletRequest) rq).subscribe(ignored -> {}, ignored -> {}));
                    rq.startAsync(rq, rs);
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertNotNull(run.get());
                assertTrue(a.registry.cancelExact(42L, run.get().clientToken()));
                subscription.get().dispose();
                var async = (org.springframework.mock.web.MockAsyncContext) requestA.getAsyncContext();
                for (var listener : async.getListeners()) listener.onTimeout(new jakarta.servlet.AsyncEvent(async));
                assertEquals(1, guard.activeLeaseCountForTest());
                assertEquals(1, guard.availableGlobalPermitsForTest());

                var responseB = new org.springframework.mock.web.MockHttpServletResponse();
                filterB.doFilter(b.admittedRequest("ordinary question", "shared-key"), responseB, (rq, rs) -> {
                    var entity = b.controller.chat(b.requestDto("ordinary question"), null,
                            (jakarta.servlet.http.HttpServletRequest) rq).block(Duration.ofSeconds(5));
                    assertNotNull(entity);
                    assertEquals(HttpStatus.OK, entity.getStatusCode());
                    assertEquals(43L, entity.getBody().getSessionId());
                    assertEquals("generated answer", entity.getBody().getContent());
                    var method = java.util.Arrays.stream(ChatApiController.class.getMethods())
                            .filter(m -> m.getName().equals("chat")).findFirst().orElseThrow();
                    new ChatRequestCompletionAdvice().beforeBodyWrite(entity.getBody(),
                            new org.springframework.core.MethodParameter(method, -1),
                            org.springframework.http.MediaType.APPLICATION_JSON,
                            org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class,
                            new org.springframework.http.server.ServletServerHttpRequest((jakarta.servlet.http.HttpServletRequest) rq),
                            new org.springframework.http.server.ServletServerHttpResponse((jakarta.servlet.http.HttpServletResponse) rs));
                    rs.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(entity.getBody()));
                });
                assertEquals(1, exited.getCount(), "B completes while A's cancelled delegate is still alive");
                assertFalse(a.registry.isRunning(43L));
                assertEquals(1, guard.activeLeaseCountForTest());
                verify(b.history, times(1)).appendMessageReturningId(43L, "assistant", "generated answer");
                verify(b.history, never()).appendMessageReturningId(eq(42L), anyString(), anyString());

                var replayB = new org.springframework.mock.web.MockHttpServletResponse();
                filterB.doFilter(b.admittedRequest("ordinary question", "shared-key"), replayB,
                        (rq, rs) -> fail("completed B retry must replay without controller generation"));
                assertEquals(200, replayB.getStatus());
                assertEquals("true", replayB.getHeader("X-Idempotent-Replay"));
                assertEquals(responseB.getContentAsString(), replayB.getContentAsString());
                assertFalse(replayB.getContentAsString().contains(run.get().clientToken()));
                for (String message : List.of("ordinary question", "changed question")) {
                    var retryA = new org.springframework.mock.web.MockHttpServletResponse();
                    filterA.doFilter(a.admittedRequest(message, "shared-key"), retryA,
                            (rq, rs) -> fail("cancelled A claim must never execute or replay B's result"));
                    assertEquals(409, retryA.getStatus());
                    assertTrue(retryA.getContentAsString().contains("ordinary question".equals(message)
                            ? "idempotency_duplicate" : "idempotency_payload_mismatch"));
                    assertFalse(retryA.getContentAsString().contains("generated answer"));
                }
                var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
                assertEquals("OUTCOME_UNKNOWN", jdbc.queryForObject(
                        "SELECT state FROM awx_chat_requests WHERE owner_hash=?", String.class,
                        org.apache.commons.codec.digest.DigestUtils.sha256Hex("owner:owner-a")));
                assertEquals("COMPLETED", jdbc.queryForObject(
                        "SELECT state FROM awx_chat_requests WHERE owner_hash=?", String.class,
                        org.apache.commons.codec.digest.DigestUtils.sha256Hex("owner:owner-b")));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM awx_chat_request_results", Integer.class));
                release.countDown();
                assertTrue(exited.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    while (guard.activeLeaseCountForTest() != 0) Thread.sleep(1);
                });
                assertEquals(0, guard.activeOwnerCountForTest());
                assertEquals(2, guard.availableGlobalPermitsForTest());
                verify(a.chat, times(1)).continueChat(any(ChatRequestDto.class), any());
                verify(b.chat, times(1)).continueChat(any(ChatRequestDto.class), any());
                verify(a.history, never()).appendMessageReturningId(anyLong(), eq("assistant"), anyString());
                verify(a.history, never()).updateRollingSummary(anyLong(), nullable(Long.class));
            } finally {
                release.countDown();
                if (subscription.get() != null) subscription.get().dispose();
                if (entered.getCount() == 0) assertTrue(exited.await(2, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "stream"})
    void cancelledBlockingWorkerRetainsAdmissionUntilItActuallyExits(String route) throws Exception {
        try (Fixture f = new Fixture()) {
            PublicChatAdmissionGuard guard = new PublicChatAdmissionGuard(1, 1, 4);
            ReflectionTestUtils.setField(f.controller, "publicChatAdmissionGuard", guard);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch exited = new CountDownLatch(1);
            var run = new java.util.concurrent.atomic.AtomicReference<ChatRunExecutionContext>();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                run.set(ChatRunExecutionContext.current());
                entered.countDown();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (release.getCount() != 0) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) throw new IllegalStateException("synthetic worker timeout");
                        try { release.await(remaining, TimeUnit.NANOSECONDS); }
                        catch (InterruptedException ignored) { /* Deliberately non-cooperative transport. */ }
                    }
                    throw new CancellationException("synthetic cancelled worker");
                } finally {
                    exited.countDown();
                }
            });
            ChatRequestDto request = ChatRequestDto.builder().message("ordinary question")
                    .sessionId(42L).useRag(false).useWebSearch(false).build();
            reactor.core.Disposable subscription = "chat".equals(route)
                    ? f.controller.chat(request, null, new MockHttpServletRequest()).subscribe()
                    : f.controller.chatStream(request, false, false, null, new MockHttpServletRequest()).subscribe();
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertNotNull(run.get());
                assertTrue(f.registry.cancelExact(42L, run.get().clientToken()));
                subscription.dispose();
                assertEquals(1, exited.getCount(), "cancellation has not stopped the blocking delegate");
                var otherOwner = com.example.lms.service.AttachmentOwnerIdentity
                        .forActor("anonymousUser", "owner-b").hash();
                var unexpected = guard.tryAcquire(otherOwner);
                unexpected.ifPresent(PublicChatAdmissionGuard.Lease::close);
                assertTrue(unexpected.isEmpty(), "running cancelled work must still occupy global capacity");
                assertEquals(1, guard.activeLeaseCountForTest());
                assertEquals(0, guard.availableGlobalPermitsForTest());
                release.countDown();
                assertTrue(exited.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    while (guard.activeLeaseCountForTest() != 0) Thread.sleep(1);
                });
                try (var available = guard.tryAcquire(otherOwner).orElseThrow()) {
                    assertEquals(1, guard.activeLeaseCountForTest());
                }
                assertEquals(0, guard.activeOwnerCountForTest());
                assertEquals(1, guard.availableGlobalPermitsForTest());
                verify(f.chat, times(1)).continueChat(any(ChatRequestDto.class), any());
                verify(f.history, never()).appendMessageReturningId(anyLong(), eq("assistant"), anyString());
            } finally {
                release.countDown();
                subscription.dispose();
                assertTrue(exited.await(2, TimeUnit.SECONDS));
            }
        }
    }

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
            verify(f.history).updateRollingSummary(eq(42L), nullable(Long.class));
        }
    }


    @ParameterizedTest
    @ValueSource(strings = {"stream", "sync"})
    void degradedGeneratedAnswerIsPersistedAndDeliveredAsFinal(String route) {
        try (Fixture f = new Fixture()) {
            String answer = "[품질 저하] 답변 후처리를 완료하지 못했습니다.\n\nComplete synthetic answer.";
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                com.example.lms.search.TraceStore.put("finalAnswer.postprocess.reason", "postprocess_failed");
                com.example.lms.search.TraceStore.put("finalAnswer.memorySaveAllowed", false);
                return ChatResult.of(answer, "fixture-model", false, java.util.Set.of());
            });
            when(f.history.appendMessageReturningId(42L, "assistant", answer)).thenReturn(421L);
            if ("sync".equals(route)) {
                var response = f.request("sync");
                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertEquals(answer, response.getBody().getContent());
                verify(f.history, times(1)).appendMessageReturningId(42L, "assistant", answer);
                verify(f.history, never()).updateRollingSummary(anyLong(), nullable(Long.class));
                assertFalse(f.registry.isRunning(42L));
                return;
            }
            var req = ChatRequestDto.builder().message("ordinary question").sessionId(42L)
                    .useRag(false).useWebSearch(false).build();
            var events = f.controller.chatStream(req, false, false, null, new MockHttpServletRequest())
                    .collectList().block(Duration.ofSeconds(5));
            var finalEvent = events.stream().map(org.springframework.http.codec.ServerSentEvent::data)
                    .filter(e -> e != null && "final".equals(e.type())).findFirst().orElseThrow();
            assertEquals(answer, finalEvent.data());
            assertFalse(events.stream().map(org.springframework.http.codec.ServerSentEvent::data)
                    .anyMatch(e -> e != null && "error".equals(e.type())));
            verify(f.history, times(1)).appendMessageReturningId(42L, "assistant", answer);
            verify(f.history, never()).updateRollingSummary(anyLong(), nullable(Long.class));
            assertFalse(f.registry.isRunning(42L));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ChatHistoryService history = mock(ChatHistoryService.class);
        final ChatService chat = mock(ChatService.class);
        final ChatRunRegistry registry;
        final ClientOwnerKeyResolver owners = mock(ClientOwnerKeyResolver.class);
        final long sessionId;
        final boolean ownsRegistry;
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final AtomicBoolean deleted = new AtomicBoolean();
        final AtomicInteger appendAfterDelete = new AtomicInteger();
        final List<String> events = new CopyOnWriteArrayList<>();
        final ChatApiController controller;

        Fixture() {
            this("owner-a", 42L, null);
        }

        Fixture(String owner, long sessionId, ChatRunRegistry sharedRegistry) {
            this.sessionId = sessionId;
            ownsRegistry = sharedRegistry == null;
            registry = ownsRegistry ? spy(new ChatRunRegistry()) : sharedRegistry;
            ReflectionTestUtils.setField(registry, "replayCapacity", 32);
            ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
            SettingsService settings = mock(SettingsService.class);
            when(settings.getAllSettings()).thenReturn(Map.of());
            when(owners.ownerKey()).thenReturn(owner);
            when(owners.clientIpHash(any())).thenReturn("f".repeat(64));
            controller = new ChatApiController(history, chat, null, settings, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    new com.fasterxml.jackson.databind.ObjectMapper(), null, registry, owners);
            ChatSession session = new ChatSession("lifecycle", owner, "ANON");
            session.setId(sessionId);
            when(history.getSessionWithMessages(sessionId)).thenAnswer(invocation -> deleted.get() ? null : session);
            when(history.getSessionForRequest(sessionId)).thenAnswer(invocation -> deleted.get() ? null : session);
            when(history.getSessionWithMessages(sessionId, 1)).thenAnswer(invocation -> deleted.get() ? null : session);
            doAnswer(invocation -> {
                events.add("delete-history");
                deleted.set(true);
                return null;
            }).when(history).deleteSession(sessionId);
            when(history.appendMessageReturningId(sessionId, "assistant", "generated answer")).thenAnswer(invocation -> {
                if (deleted.get()) appendAfterDelete.incrementAndGet();
                return deleted.get() ? null : sessionId * 10 + 1;
            });
            when(chat.continueChat(any(ChatRequestDto.class), any()))
                    .thenReturn(ChatResult.of("generated answer", "mock-model", false));
        }

        ResponseEntity<ChatResponseDto> request(String route) {
            ChatRequestDto request = requestDto("ordinary question");
            return "sync".equals(route) ? controller.chatSync(request, null, new MockHttpServletRequest())
                    : controller.chat(request, null, new MockHttpServletRequest()).block(Duration.ofSeconds(5));
        }

        ChatRequestDto requestDto(String message) {
            return ChatRequestDto.builder().message(message).sessionId(sessionId)
                    .useRag(false).useWebSearch(false).build();
        }

        MockHttpServletRequest admittedRequest(String message, String key) throws Exception {
            var request = new MockHttpServletRequest("POST", "/api/chat");
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(requestDto(message));
            request.setContentType("application/json");
            request.setContent(body);
            request.setAttribute("chat.admission.bodySha256", org.apache.commons.codec.digest.DigestUtils.sha256Hex(body));
            request.addHeader("Idempotency-Key", key);
            request.setAsyncSupported(true);
            return request;
        }

        @Override public void close() {
            executor.shutdownNow();
            if (ownsRegistry) ReflectionTestUtils.invokeMethod(registry, "shutdown");
        }
    }
}
