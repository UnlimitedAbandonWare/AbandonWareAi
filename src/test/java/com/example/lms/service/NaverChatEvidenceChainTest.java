package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic transport only: preserves the production provider, controller and evidence path. */
class NaverChatEvidenceChainTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private static final String URL = "https://example.org/naver-synthetic-314159";
    private static final String PHRASE = "NAVER synthetic coffee observation 314159";
    private static final String DESCRIPTION = "Original NAVER description contains distinct observation 271828. 오늘 발표된 합성 커피 관측 결과";
    private static final String REQUEST_ID = "synthetic-naver-chat-chain";

    @ParameterizedTest
    @ValueSource(strings = {"valid", "cache", "old", "no-receipt", "foreign-provider", "invalid-id",
            "foreign-execution", "wrong-owner", "wrong-session", "different-run", "different-correlation", "cancelled", "unbound", "masked", "mask-wrong-attempt", "mask-wrong-search", "mask-wrong-retrieval",
            "mask-no-brave", "mask-cached-brave", "mask-error-brave", "mask-zero-brave", "mask-success-naver", "mask-stale-brave", "mask-unparsed-brave"})
    void prefetchHandoffRejectsForeignStaleCachedAndUnownedRows(String variant) {
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        var otherRegistry = new com.example.lms.service.chat.ChatRunRegistry();
        for (var r : List.of(registry, otherRegistry)) {
            ReflectionTestUtils.setField(r, "ttlSeconds", 60);
            ReflectionTestUtils.setField(r, "replayCapacity", 32);
        }
        var run = registry.beginOrJoin(901L).context();
        var otherRun = otherRegistry.beginOrJoin(901L).context();
        var request = ChatRequestDto.builder().sessionId(901L).build();
        request.bindVerifiedRequestOwner(AttachmentOwnerIdentity.forAnonymous("synthetic-owner"));
        Map<String, String> priorMdc = org.slf4j.MDC.getCopyOfContextMap();
        try (var binding = "unbound".equals(variant) ? null
                : com.example.lms.service.chat.ChatRunExecutionContext.bind(run)) {
            TraceStore.clear();
            org.slf4j.MDC.put("traceId", REQUEST_ID);
            org.slf4j.MDC.put("x-request-id", REQUEST_ID);
            TraceStore.put("trace.id", com.example.lms.trace.SafeRedactor.hashValue(REQUEST_ID));
            long now = System.currentTimeMillis(), since = now - 1000;
            var row = new java.util.HashMap<String, Object>();
            row.put("provider", "foreign-provider".equals(variant) ? "brave" : "naver");
            row.put("searchExecutionId", "hash:111111111111");
            final String retrievalExecutionId = "hash:333333333333";
            row.put("retrievalExecutionId", "foreign-execution".equals(variant) ? "hash:444444444444" : retrievalExecutionId);
            row.put("providerAttemptId", "invalid-id".equals(variant) ? "raw-private-id" : "hash:222222222222");
            row.put("startedAtEpochMs", "old".equals(variant) ? since - 1 : now - 100);
            row.put("finishedAtEpochMs", now);
            row.put("clientAttemptObserved", true);
            row.put("providerReceiptObserved", !"no-receipt".equals(variant));
            row.put("httpStatus", 200);
            row.put("failureClass", "NONE");
            row.put("returnedCount", 1);
            row.put("cacheHit", "cache".equals(variant));
            row.put("privateBody", "must-not-cross-clear");
            boolean maskCase = "masked".equals(variant) || variant.startsWith("mask-");
            if (maskCase) {
                row.put("httpStatus", "mask-success-naver".equals(variant) ? 200 : 401);
                row.put("failureClass", "mask-success-naver".equals(variant) ? "NONE" : "AUTH_OR_CONFIG");
                var braveRow = new java.util.HashMap<>(row);
                braveRow.put("provider", "brave");
                braveRow.put("providerAttemptId", "hash:555555555555");
                braveRow.put("retrievalExecutionId", "mask-wrong-retrieval".equals(variant) ? "hash:444444444444" : retrievalExecutionId);
                braveRow.put("failureClass", "NONE");
                braveRow.put("outcome", "mask-error-brave".equals(variant) ? "HTTP_ERROR" : "OK");
                braveRow.put("httpStatus", "mask-error-brave".equals(variant) ? 401 : 200);
                braveRow.put("afterFilterCount", "mask-zero-brave".equals(variant) ? 0 : 1);
                braveRow.put("cacheHit", "mask-cached-brave".equals(variant));
                braveRow.put("providerReceiptObserved", !"mask-unparsed-brave".equals(variant));
                braveRow.put("startedAtEpochMs", "mask-stale-brave".equals(variant) ? since - 1 : now - 100);
                if (!"mask-no-brave".equals(variant)) TraceStore.put("web.brave.attempt.runs", List.of(braveRow));
                TraceStore.put("web.naver.masked.runs", List.of(Map.of(
                        "providerAttemptId", "mask-wrong-attempt".equals(variant) ? "hash:666666666666" : row.get("providerAttemptId"),
                        "searchExecutionId", "mask-wrong-search".equals(variant) ? "hash:777777777777" : row.get("searchExecutionId"),
                        "maskedBy", "brave")));
            }
            TraceStore.put("web.naver.filter.runs", List.of(row));
            ReflectionTestUtils.invokeMethod(ChatWorkflow.class, "captureControllerSearchReceipts", request, since, retrievalExecutionId);
            if ("wrong-owner".equals(variant)) {
                request = ChatRequestDto.builder().sessionId(901L).build();
                request.bindVerifiedRequestOwner(AttachmentOwnerIdentity.forAnonymous("different-synthetic-owner"));
            }
            if ("wrong-session".equals(variant)) request.setSessionId(999L);
            if ("different-correlation".equals(variant)) {
                org.slf4j.MDC.put("traceId", "different-request");
                org.slf4j.MDC.put("x-request-id", "different-request");
            }
            if ("cancelled".equals(variant)) assertTrue(registry.cancelExact(901L, run.clientToken()));
            try (var different = "different-run".equals(variant)
                    ? com.example.lms.service.chat.ChatRunExecutionContext.bind(otherRun) : null) {
                Object receipt = ReflectionTestUtils.invokeMethod(ChatWorkflowRequestTraceEnvelope.class,
                        "takeControllerPrefetch", request);
                TraceStore.clear();
                ChatWorkflowRequestTraceEnvelope.seed(request, "chat-901");
                ReflectionTestUtils.invokeMethod(ChatWorkflowRequestTraceEnvelope.class,
                        "restoreControllerPrefetch", request, receipt);
                var restored = TraceStore.get("web.naver.filter.runs");
                if ("valid".equals(variant) || maskCase) {
                    assertNotNull(restored);
                    var copied = (Map<?, ?>) ((List<?>) restored).get(0);
                    assertEquals("masked".equals(variant) ? "brave" : null, copied.get("maskedBy"));
                    assertNull(TraceStore.get("web.naver.masked.runs"), "never restore an unbounded scalar/join list");
                    assertFalse(restored.toString().contains("privateBody") || restored.toString().contains("must-not-cross-clear"));
                } else assertNull(restored, "rejected handoff: " + variant);
            }
        } finally {
            ReflectionTestUtils.invokeMethod(registry, "shutdown");
            ReflectionTestUtils.invokeMethod(otherRegistry, "shutdown");
            TraceStore.clear();
            if (priorMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(priorMdc);
        }
    }

    @org.junit.jupiter.api.Test
    void acceptedSearchUsesItsSelectedResourceBudgetWithoutRereadingIngressContext() {
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        var run = registry.beginOrJoin(902L).context();
        var executor = new com.example.lms.infra.exec.ContextAwareExecutorService(java.util.concurrent.Executors.newFixedThreadPool(2));
        try (var binding = com.example.lms.service.chat.ChatRunExecutionContext.bind(run)) {
            assertTrue(com.example.lms.service.chat.ChatRunExecutionContext.isAcceptedExecution());
            com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(3000));
            assertNull(com.abandonware.ai.addons.budget.TimeBudgetContext.get(), "accepted run intentionally ignores ingress context");
            TraceStore.clear();
            var naverFixture = new NaverSearchServiceApiHubTest();
            var env = new MockEnvironment().withProperty("naver.search.provider", "apihub")
                    .withProperty("naver.apihub.client-id", "synthetic-budget-id")
                    .withProperty("naver.apihub.client-secret", "synthetic-budget-secret");
            NaverSearchService naver = ReflectionTestUtils.invokeMethod(naverFixture, "service", "", env, HttpStatus.OK);
            var brave = mock(BraveSearchService.class);
            when(brave.isEnabled()).thenReturn(true);
            when(brave.searchWithMeta(anyString(), anyInt())).thenReturn(BraveSearchResult.ok(List.of(), 1));
            var web = new HybridWebSearchProvider(naver, brave);
            ReflectionTestUtils.setField(web, "searchIoExecutor", executor);
            ReflectionTestUtils.setField(web, "boundedFallbackEnabled", false);
            ReflectionTestUtils.setField(web, "primary", "NAVER");
            ReflectionTestUtils.setField(web, "timeoutSec", 3);
            var snippets = web.search("합성 커피 검색", 3);
            assertFalse(snippets.isEmpty());
            assertEquals(1, ((List<?>) ReflectionTestUtils.getField(naverFixture, "requests")).size());
        } finally {
            executor.shutdownNow();
            ReflectionTestUtils.invokeMethod(registry, "shutdown");
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
            TraceStore.clear();
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"200,false,BRAVE,KO", "401,false,BRAVE,KO",
            "401,true,NAVER,KO", "401,true,BRAVE,KO", "401,true,NAVER,EN"})
    @SuppressWarnings("unchecked")
    void onlyParsedNaverReceiptCanReachTheSameRequestPromptCitationAndFinalAnswer(int status, boolean braveFallback, String primary, String language) throws Exception {
        Object fixture = ReflectionTestUtils.invokeMethod(
                ChatWorkflowFinalVerificationReleaseGateTest.class, "memoryHoldFixture");
        ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(fixture, "workflow");
        ChatModel model = ReflectionTestUtils.invokeMethod(fixture, "model");
        ReflectionTestUtils.invokeMethod(ChatWorkflowDynamicAutoSocialTest.class, "installLocalCatalog", workflow);
        ReflectionTestUtils.invokeMethod(ChatWorkflowPromptMessageRoleTest.class, "configureRetrieval", workflow, 3);
        var preprocessor = (com.example.lms.service.rag.pre.QueryContextPreprocessor)
                ReflectionTestUtils.getField(workflow, "qcPreprocessor");
        when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
        var hybridRetriever = (com.example.lms.service.rag.HybridRetriever)
                ReflectionTestUtils.getField(workflow, "hybridRetriever");
        when(hybridRetriever.retrieveAll(anyList(), anyInt(), any(), any())).thenReturn(List.of());
        var rag = (com.example.lms.service.rag.LangChainRAGService) ReflectionTestUtils.getField(workflow, "ragSvc");
        when(rag.asContentRetriever(nullable(String.class))).thenReturn(query -> List.of());
        ReflectionTestUtils.setField(workflow, "cancelFlags", new java.util.concurrent.ConcurrentHashMap<>());
        ReflectionTestUtils.setField(workflow, "freeIdeaCount", new java.util.concurrent.atomic.AtomicLong());
        var factory = mock(com.example.lms.llm.DynamicChatModelFactory.class, call -> {
            if (List.of("lcWithTimeout", "lcForPreparedAnswer").contains(call.getMethod().getName())) {
                assertEquals("chatgpt-oauth:gpt-5.6-luna", call.getArgument(0), "retain the admitted selected route");
                return model;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", factory);
        var profiles = new com.example.lms.guard.GuardProfileProps();
        profiles.setProfile("PROFILE_MEMORY");
        var gate = new com.example.lms.service.rag.guard.EvidenceGate(.05, .02, .6, .8, false);
        ReflectionTestUtils.setField(gate, "guardProfileProps", profiles);
        ReflectionTestUtils.setField(workflow, "ragEvidenceAttributionService",
                new com.example.lms.service.rag.RagEvidenceAttributionService(
                        gate, new com.example.lms.service.guard.CitationGate(), null));
        var verifier = (FactVerifierService) ReflectionTestUtils.getField(workflow, "verifier");
        when(verifier.verifyDetailed(anyString(), nullable(String.class), nullable(String.class),
                anyString(), anyString(), anyBoolean())).thenAnswer(call ->
                        new FactVerifierService.DetailedVerificationResult(call.getArgument(3), "pass", true, true));

        AtomicReference<String> generationRequest = new AtomicReference<>();
        AtomicReference<Map<String, Object>> generationTrace = new AtomicReference<>(Map.of());
        AtomicReference<Boolean> sourceReachedPrompt = new AtomicReference<>(false);
        when(model.chat(anyList())).thenAnswer(call -> {
            List<dev.langchain4j.data.message.ChatMessage> messages = call.getArgument(0);
            String prompt = messages.stream().map(message -> message instanceof dev.langchain4j.data.message.SystemMessage s
                    ? s.text() : message instanceof dev.langchain4j.data.message.UserMessage u ? u.singleText() : "")
                    .collect(java.util.stream.Collectors.joining("\n"));
            boolean found = prompt.contains(PHRASE) && prompt.contains(DESCRIPTION) && prompt.contains(URL);
            sourceReachedPrompt.set(found);
            generationRequest.set(org.slf4j.MDC.get("x-request-id"));
            generationTrace.set(TraceStore.getAll());
            // The synthetic answer depends on the actual production prompt, never separate fixture documents.
            return dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(
                    dev.langchain4j.data.message.AiMessage.from(found ? PHRASE + ": " + DESCRIPTION + " [W1]"
                            : "검색 근거를 확인하지 못했습니다.")).build();
        });

        var recorder = new com.example.lms.debug.ApiFailureRecorder(mock(com.example.lms.debug.DebugEventStore.class),
                temporary.resolve("incidents.json").toString());
        var naverFixture = new NaverSearchServiceApiHubTest();
        String body = "{\"items\":[{\"title\":\"" + PHRASE + "\",\"link\":\"" + URL
                + "\",\"description\":\"" + DESCRIPTION + "\"}]}";
        var env = new MockEnvironment().withProperty("naver.search.provider", "apihub")
                .withProperty("naver.apihub.client-id", "synthetic-chain-id")
                .withProperty("naver.apihub.client-secret", "synthetic-chain-secret");
        NaverSearchService naver = ReflectionTestUtils.invokeMethod(naverFixture, "service",
                "", env, HttpStatus.valueOf(status), recorder, status == 200 ? body : "{}");
        BraveSearchService brave;
        com.sun.net.httpserver.HttpServer braveServer = braveFallback
                ? com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0) : null;
        var braveWireCount = new java.util.concurrent.atomic.AtomicInteger();
        if (braveFallback) {
            brave = ReflectionTestUtils.invokeMethod(Class.forName("com.example.lms.service.web.BraveSearchServiceResponseShapeTest"), "enabledService");
            ReflectionTestUtils.setField(brave, "apiFailureRecorder", recorder);
            byte[] braveBody = ("{\"type\":\"search\",\"web\":{\"results\":[{\"title\":\"" + PHRASE
                    + "\",\"url\":\"" + URL + "\",\"description\":\"" + DESCRIPTION + "\"}]}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            braveServer.createContext("/res/v1/web/search", exchange -> {
                braveWireCount.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, braveBody.length);
                try (var output = exchange.getResponseBody()) { output.write(braveBody); }
                finally { exchange.close(); }
            });
            braveServer.start();
            ReflectionTestUtils.setField(brave, "baseUrl", "http://127.0.0.1:" + braveServer.getAddress().getPort() + "/res/v1/web/search");
            assertEquals("127.0.0.1", java.net.URI.create((String) ReflectionTestUtils.getField(brave, "baseUrl")).getHost());
        } else {
            brave = mock(BraveSearchService.class);
            when(brave.isEnabled()).thenReturn(true);
            when(brave.searchWithMeta(anyString(), anyInt())).thenReturn(BraveSearchResult.ok(List.of(), 1));
        }
        var searchExecutor = new com.example.lms.infra.exec.ContextAwareExecutorService(java.util.concurrent.Executors.newFixedThreadPool(2));
        var web = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(web, "searchIoExecutor", searchExecutor);
        ReflectionTestUtils.setField(web, "boundedFallbackEnabled", !braveFallback);
        ReflectionTestUtils.setField(web, "primary", primary);
        ReflectionTestUtils.setField(web, "apiFailureRecorder", recorder);
        ReflectionTestUtils.setField(web, "timeoutSec", 3);

        var history = mock(ChatHistoryService.class);
        ReflectionTestUtils.setField(workflow, "chatHistoryService", history);
        var settings = mock(SettingsService.class);
        var owner = mock(com.example.lms.web.ClientOwnerKeyResolver.class);
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        var session = new com.example.lms.domain.ChatSession("NAVER synthetic chain", "synthetic-owner", "ANON");
        session.setId(801L);
        when(settings.getAllSettings()).thenReturn(Map.of());
        when(owner.ownerKey()).thenReturn("synthetic-owner");
        when(history.getSessionForRequest(801L)).thenReturn(session);
        when(history.getSessionWithMessages(801L)).thenReturn(session);
        var messageIds = new java.util.concurrent.atomic.AtomicLong(900);
        when(history.appendMessageReturningId(anyLong(), anyString(), anyString()))
                .thenAnswer(call -> messageIds.incrementAndGet());
        com.example.lms.api.ChatApiController controller = ReflectionTestUtils.invokeMethod(
                Class.forName("com.example.lms.api.ChatApiControllerInputGuardTest"), "controller",
                history, new ChatService(workflow), settings, owner, registry, web, null);
        var boundary = new com.example.lms.orchestration.control.RagControlPresentationBoundary(
                new com.example.lms.orchestration.control.RagControlCoordinator(
                        new com.example.lms.orchestration.control.RagGuardProbeComposer(),
                        new com.example.lms.orchestration.control.RagControlRolloutState(
                                new com.example.lms.orchestration.control.RagControlProperties(20, .01d, 20))),
                new com.example.lms.orchestration.control.RagControlRuntimeAdapter(),
                new com.example.lms.orchestration.control.RagControlProjectionRenderer(),
                (org.springframework.beans.factory.ObjectProvider<com.example.lms.llm.ModelRuntimeHealthTracker>) null);
        ReflectionTestUtils.setField(controller, "ragControlPresentationBoundary", boundary);
        ReflectionTestUtils.invokeMethod(ChatWorkflowDynamicAutoSocialTest.class, "clearState");
        try {
            var request = ChatRequestDto.builder().message("EN".equals(language)
                    ? "Explain the synthetic coffee observation announced today and cite the source"
                    : "오늘 발표된 합성 커피 관측 결과를 출처와 함께 알려줘")
                    .sessionId(801L).model("chatgpt-oauth:gpt-5.6-luna").modelSelectionMode("preferred")
                    .executionMode(com.example.lms.domain.enums.ExecutionMode.AUTO).maxTokens(512)
                    .searchMode(com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT)
                    .useWebSearch(true).useRag(true).useVerification(false).memoryMode("OFF").build();
            var servlet = new MockHttpServletRequest("POST", "/api/chat/stream");
            servlet.addHeader("X-Request-Id", REQUEST_ID);
            var stream = controller.chatStream(request, false, false, null, servlet);
            String token = registry.currentRunToken(801L).orElseThrow();
            assertEquals(true, controller.acknowledgeRun(null,
                    Map.of("sessionId", 801L, "runToken", token), null, null).getBody().get("acknowledged"));
            var events = stream.collectList().block(Duration.ofSeconds(12));
            assertNotNull(events);
            List<ChatStreamEvent> finals = events.stream().map(org.springframework.http.codec.ServerSentEvent::data)
                    .filter(event -> event != null && "final".equals(event.type())).toList();
            assertEquals(1, finals.size(), () -> "wireCount="
                    + ((List<?>) ReflectionTestUtils.getField(naverFixture, "requests")).size()
                    + " modelMethods=" + mockingDetails(model).getInvocations().stream()
                            .map(call -> call.getMethod().getName()).distinct().toList()
                    + " factoryMethods=" + mockingDetails(factory).getInvocations().stream()
                            .map(call -> call.getMethod().getName()).distinct().toList()
                    + " eventTypes=" + events.stream().map(event -> event.data() == null ? "empty" : event.data().type()).toList());
            var last = finals.get(0);
            assertEquals(801L, last.sessionId());
            var wires = (List<ClientRequest>) ReflectionTestUtils.getField(naverFixture, "requests");
            assertEquals(1, wires.size(), "fresh request must perform one real client subscription, never cache-only proof");
            assertEquals("naverapihub.apigw.ntruss.com", wires.get(0).url().getHost());
            assertEquals(REQUEST_ID, wires.get(0).headers().getFirst("x-request-id"));
            assertEquals(REQUEST_ID, generationRequest.get());
            assertTrue(events.stream().map(org.springframework.http.codec.ServerSentEvent::data)
                    .filter(event -> event != null && event.traceSignal() != null)
                    .anyMatch(event -> com.example.lms.trace.SafeRedactor.hashValue(REQUEST_ID)
                            .equals(event.traceSignal().requestIdHash())), "final stream must carry the same request trace hash");
            assertFalse(last.data().contains("backend_unavailable") || last.data().contains("HOLD"));
            var attempts = (List<Map<String, Object>>) generationTrace.get().get("web.naver.filter.runs");
            assertNotNull(attempts, "controller prefetch receipt must survive only within its same admitted request");
            if (status == 200 || braveFallback) {
                assertTrue(sourceReachedPrompt.get(), "actual parsed NAVER URL and body must reach generation");
                assertTrue(last.data().contains(PHRASE) && last.data().contains(DESCRIPTION) && last.data().contains("[W1]"),
                        () -> "synthetic final phrase=" + last.data().contains(PHRASE)
                                + " description=" + last.data().contains(DESCRIPTION) + " marker=" + last.data().contains("[W1]")
                                + " evidence=" + (last.evidence() == null ? "none" : last.evidence().stream().map(e -> e.marker()).toList()));
                assertEquals(1, generationTrace.get().get("rag.evidence.promotion.promotedCount"),
                        () -> "promotion reason=" + generationTrace.get().get("rag.evidence.promotion.disabledReason")
                                + " candidates=" + generationTrace.get().get("rag.evidence.promotion.candidateCount"));
                assertTrue(last.evidence().stream().anyMatch(evidence -> URL.equals(evidence.source())
                        && "WEB".equals(evidence.kind()) && "W1".equals(evidence.marker())),
                        () -> "synthetic final metadata=" + last.evidence().stream()
                                .map(e -> e.kind() + ":" + e.marker() + ":sourceMatches=" + URL.equals(e.source())).toList());
            } else {
                assertFalse(sourceReachedPrompt.get());
                assertFalse(last.data().contains(PHRASE));
                assertTrue(last.evidence() == null || last.evidence().stream().noneMatch(e -> URL.equals(e.source())));
            }
            assertEquals(1, attempts.size(), "fresh cleared request must retain exactly its one client receipt");
            assertTrue(String.valueOf(attempts.get(0).get("searchExecutionId")).matches("hash:[a-f0-9]{12}"));
            assertTrue(String.valueOf(attempts.get(0).get("providerAttemptId")).matches("hash:[a-f0-9]{12}"));
            assertTrue(attempts.stream().anyMatch(attempt -> Boolean.TRUE.equals(attempt.get("providerReceiptObserved"))
                    && Integer.valueOf(status).equals(attempt.get("httpStatus"))
                    && (status == 200 ? "NONE" : "AUTH_OR_CONFIG").equals(attempt.get("failureClass"))));
            if (braveFallback) {
                assertEquals(1, braveWireCount.get());
                var incident = recorder.snapshot().stream().filter(i -> "naver".equals(i.provider())).findFirst().orElseThrow();
                assertEquals("brave/web", incident.maskedBy());
                assertEquals(401, incident.httpStatus());
                assertEquals(1, incident.consecutive());
                assertNull(incident.recoveredAt(), "Brave evidence must never recover NAVER");
                assertEquals("brave", attempts.get(0).get("maskedBy"), "same-request mask must survive workflow trace clear");
                var snapshots = events.stream().map(org.springframework.http.codec.ServerSentEvent::data)
                        .filter(e -> e != null && e.pipelineSnapshot() != null && e.pipelineSnapshot().agentWebSearch() != null)
                        .flatMap(e -> e.pipelineSnapshot().agentWebSearch().providers().stream()).toList();
                assertTrue(snapshots.stream().anyMatch(p -> "naver".equals(p.provider()) && "brave".equals(p.maskedBy())
                        && Integer.valueOf(401).equals(p.httpStatus()) && "FAIL_SOFT".equals(p.outcome()) && p.recoveredAt() == null),
                        "typed SSE must retain the masked failure without recovery");
                assertTrue(snapshots.stream().anyMatch(p -> "brave".equals(p.provider()) && "OK".equals(p.outcome())
                        && Integer.valueOf(200).equals(p.httpStatus()) && Boolean.TRUE.equals(p.providerReceiptObserved())),
                        "typed SSE must preserve the real successful Brave receipt");
            }
            verify(history).appendMessageReturningId(801L, "assistant", last.data());
            verify(model, times(1)).chat(anyList());
            assertEquals(true, controller.acknowledgeRun(null,
                    Map.of("sessionId", 801L, "runToken", token, "phase", "final"), null, null)
                    .getBody().get("acknowledged"));
            assertTrue(registry.describeExact(801L, token).orElseThrow().outcome().finalDeliveryAccepted());
        } finally {
            if (braveServer != null) braveServer.stop(0);
            searchExecutor.shutdownNow();
            recorder.close();
            ReflectionTestUtils.invokeMethod(registry, "shutdown");
            ReflectionTestUtils.invokeMethod(ChatWorkflowDynamicAutoSocialTest.class, "clearState");
        }
    }
}
