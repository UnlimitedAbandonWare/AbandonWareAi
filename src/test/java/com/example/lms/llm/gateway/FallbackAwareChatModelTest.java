package com.example.lms.llm.gateway;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OllamaNativeChatModel;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FallbackAwareChatModelTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        TimeBudgetContext.clear();
    }

    @Test
    void retriesOnceOnFallbackRoute() {
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw new RuntimeException("model not found");
            }
        };
        ChatModel fallback = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("fallback ok"))
                        .build();
            }
        };
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                primary,
                () -> fallback,
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3");

        ChatResponse response = model.chat(List.<ChatMessage>of(UserMessage.from("probe")));

        assertEquals("fallback ok", response.aiMessage().text());
        assertEquals("MODEL_MISSING", TraceStore.get("llm.gateway.fallbackAware.primaryFailure"));
        assertEquals(true, TraceStore.get("llm.gateway.fallbackAware.sameRequestRetry"));
        assertNull(TraceStore.get("llm.gateway.fallbackAware.routeResolutionFailureReason"));
        assertNull(TraceStore.get("llm.gateway.fallbackAware.routeResolutionFailureCount"));
    }

    @Test
    void exhaustedOriginalRequestBudgetSkipsFallbackConstruction() {
        TimeBudget requestBudget = mock(TimeBudget.class);
        when(requestBudget.remainingMillis()).thenReturn(0L);
        TimeBudgetContext.set(requestBudget);
        AtomicInteger fallbackBuilds = new AtomicInteger();
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                org.junit.jupiter.api.Assertions.fail("an already expired request must not dispatch primary");
                return null;
            }
        };
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                primary,
                () -> {
                    fallbackBuilds.incrementAndGet();
                    return new ChatModel() {
                        @Override
                        public ChatResponse chat(List<ChatMessage> messages) {
                            return ChatResponse.builder()
                                    .aiMessage(AiMessage.from("must not run"))
                                    .build();
                        }
                    };
                },
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> model.chat(List.<ChatMessage>of(UserMessage.from("probe"))));

        assertEquals("Inference deadline exhausted", failure.getMessage());
        assertEquals(0, fallbackBuilds.get());
        assertEquals(0L, TraceStore.get("llm.gateway.fallback.remainingMs"));
        assertEquals("request_deadline_exhausted",
                TraceStore.get("llm.gateway.fallback.skippedReason"));
    }

    @Test
    void budgetExpiryDuringFallbackConstructionSkipsProviderInvocation() {
        TimeBudget requestBudget = mock(TimeBudget.class);
        // Initial dispatch and fallback admission both have time; construction consumes it.
        when(requestBudget.remainingMillis()).thenReturn(60L, 60L, 0L);
        TimeBudgetContext.set(requestBudget);
        AtomicInteger fallbackBuilds = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw new RuntimeException("model not found");
            }
        };
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                primary,
                () -> {
                    fallbackBuilds.incrementAndGet();
                    return new ChatModel() {
                        @Override
                        public ChatResponse chat(List<ChatMessage> messages) {
                            fallbackCalls.incrementAndGet();
                            return ChatResponse.builder().aiMessage(AiMessage.from("must not run")).build();
                        }
                    };
                },
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3");

        assertThrows(RuntimeException.class, () -> model.chat(List.<ChatMessage>of(UserMessage.from("probe"))));

        assertEquals(1, fallbackBuilds.get());
        assertEquals(0, fallbackCalls.get());
        assertEquals(0L, TraceStore.get("llm.gateway.fallback.remainingMs"));
        assertEquals("request_deadline_exhausted",
                TraceStore.get("llm.gateway.fallback.skippedReason"));
    }

    @Test
    void fallbackAttemptRecordsOnlyTheOriginalRequestTimeStillRemaining() {
        TimeBudget requestBudget = mock(TimeBudget.class);
        when(requestBudget.remainingMillis()).thenReturn(60L, 42L);
        TimeBudgetContext.set(requestBudget);
        AtomicInteger fallbackCalls = new AtomicInteger();
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw new RuntimeException("model not found");
            }
        };
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                primary,
                () -> new ChatModel() {
                    @Override
                    public ChatResponse chat(List<ChatMessage> messages) {
                        fallbackCalls.incrementAndGet();
                        return ChatResponse.builder().aiMessage(AiMessage.from("fallback ok")).build();
                    }
                },
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3");

        ChatResponse response = model.chat(List.<ChatMessage>of(UserMessage.from("probe")));

        assertEquals("fallback ok", response.aiMessage().text());
        assertEquals(1, fallbackCalls.get());
        assertEquals(42L, TraceStore.get("llm.gateway.fallback.remainingMs"));
        assertEquals(true, TraceStore.get("llm.gateway.fallback.started"));
    }

    @Test
    void recordsPrimaryFailureAndFallbackSuccessOnTheControllerOwnedTimeline() throws Exception {
        String rawRequestId = "request-secret-123";
        String rawSessionId = "session-secret-456";
        String rawPrimaryModel = "qwen3:8b-private";
        String rawFallbackModel = "fallback-model-private";
        String rawPrimaryRoute = "primary-route-private";
        String rawFallbackRoute = "fallback-route-private";
        String rawPrimaryUrl = "http://user:password@127.0.0.1:11435/v1?token=secret";
        String rawFallbackUrl = "http://user:password@127.0.0.1:11434/v1?token=secret";

        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline(rawRequestId, rawSessionId);
        tracker.recordRequestPhase(timelineId, "dispatch", "llmrouter.light", null, null);
        tracker.recordRequestPhase(timelineId, "pending", "llmrouter.light", null, null);
        tracker.recordRequestSelection(
                timelineId,
                "router",
                rawPrimaryModel,
                rawPrimaryUrl,
                "openai_chat_completions",
                true);
        TraceStore.put(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);

        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                primaryCalls.incrementAndGet();
                throw new RuntimeException("model not found: raw-upstream-body-private");
            }
        };
        ChatModel fallback = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                fallbackCalls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("fallback ok")).build();
            }
        };

        Method routeFactory = ModelRuntimeHealthTracker.class.getMethod(
                "redactedRequestAttemptRoute",
                String.class,
                String.class,
                String.class,
                String.class);
        Object primaryRoute = routeFactory.invoke(
                tracker,
                rawPrimaryRoute,
                rawPrimaryModel,
                rawPrimaryUrl,
                "openai_chat_completions");
        Object fallbackRoute = routeFactory.invoke(
                tracker,
                rawFallbackRoute,
                rawFallbackModel,
                rawFallbackUrl,
                "openai_chat_completions");
        Class<?> routeType = primaryRoute.getClass();
        Constructor<FallbackAwareChatModel> constructor = FallbackAwareChatModel.class.getConstructor(
                ChatModel.class,
                Supplier.class,
                LlmGatewayFailureClassifier.class,
                LlmGatewayBreadcrumbPublisher.class,
                String.class,
                String.class,
                ModelRuntimeHealthTracker.class,
                String.class,
                routeType,
                Supplier.class);
        FallbackAwareChatModel model = constructor.newInstance(
                primary,
                (Supplier<ChatModel>) () -> fallback,
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3",
                tracker,
                timelineId,
                primaryRoute,
                (Supplier<Object>) () -> fallbackRoute);

        ChatResponse response = model.chat(List.<ChatMessage>of(UserMessage.from("probe")));
        tracker.recordRequestPhase(timelineId, "terminal", null, null, "success");

        Method ledgerReader = ModelRuntimeHealthTracker.class.getMethod(
                "redactedRequestAttemptLedger",
                String.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) ledgerReader.invoke(tracker, timelineId);
        List<Map<String, Object>> requestTimeline = tracker.redactedRequestTimeline(timelineId);

        assertEquals("fallback ok", response.aiMessage().text());
        assertEquals(1, primaryCalls.get());
        assertEquals(1, fallbackCalls.get());
        assertEquals(List.of("dispatch", "pending", "terminal"),
                requestTimeline.stream().map(row -> row.get("phase")).toList());
        assertEquals("success", requestTimeline.get(2).get("terminalClass"));
        assertEquals(2, attempts.size());
        assertEquals(List.of(1, 2), attempts.stream().map(row -> row.get("sequence")).toList());
        assertEquals(List.of("primary", "fallback"), attempts.stream().map(row -> row.get("role")).toList());
        assertEquals(List.of("failed", "success"), attempts.stream().map(row -> row.get("outcome")).toList());
        assertEquals(List.of("model_missing", "none"),
                attempts.stream().map(row -> row.get("failureClass")).toList());
        assertEquals(List.of("model_unavailable", "success"),
                attempts.stream().map(row -> row.get("terminalClass")).toList());
        assertEquals(List.of("127.0.0.1:11435", "127.0.0.1:11434"),
                attempts.stream().map(row -> row.get("endpointLabel")).toList());
        assertTrue(attempts.stream().allMatch(row -> timelineId.equals(row.get("timelineId"))));
        assertTrue(attempts.stream().allMatch(row -> requestTimeline.get(0).get("requestHash").equals(row.get("requestHash"))));
        assertTrue(attempts.stream().allMatch(row -> requestTimeline.get(0).get("sessionHash").equals(row.get("sessionHash"))));
        assertEquals("HOLD", tracker.endpointRepairReadiness(timelineId).status());
        assertEquals("ambiguous_route_attempts", tracker.endpointRepairReadiness(timelineId).reason());

        String publicEvidence = attempts.toString();
        for (String raw : List.of(
                rawRequestId,
                rawSessionId,
                rawPrimaryModel,
                rawFallbackModel,
                rawPrimaryRoute,
                rawFallbackRoute,
                rawPrimaryUrl,
                rawFallbackUrl,
                "user:password",
                "/v1",
                "token=secret",
                "raw-upstream-body-private")) {
            assertFalse(publicEvidence.contains(raw), "must redact: " + raw);
        }
        assertNull(TraceStore.get("llm.gateway.fallbackAware.routeResolutionFailureReason"));
        assertNull(TraceStore.get("llm.gateway.fallbackAware.routeResolutionFailureCount"));
    }

    @Test
    void routeResolutionFailureIsRedactedAndCannotClaimEndpointAttempt() {
        String rawRouteFailure = "route-resolution-secret-must-not-leak";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = activeTimeline(
                tracker, "route-failure-request", "route-failure-session");
        AtomicInteger fallbackCalls = new AtomicInteger();
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw new RuntimeException("model not found");
            }
        };
        ChatModel fallback = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                fallbackCalls.incrementAndGet();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("fallback ok"))
                        .build();
            }
        };
        ModelRuntimeHealthTracker.RequestAttemptRoute primaryRoute =
                tracker.redactedRequestAttemptRoute(
                        "primary-private",
                        "qwen3:8b-private",
                        "http://127.0.0.1:11435/v1",
                        "openai_chat_completions");
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                primary,
                () -> fallback,
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3",
                tracker,
                timelineId,
                primaryRoute,
                () -> {
                    throw new IllegalStateException(rawRouteFailure);
                });

        ChatResponse first = model.chat(List.<ChatMessage>of(UserMessage.from("probe")));
        assertEquals(
                1L,
                TraceStore.get(
                        "llm.gateway.fallbackAware.routeResolutionFailureCount"));
        ChatResponse second = model.chat(List.<ChatMessage>of(UserMessage.from("probe")));
        List<Map<String, Object>> attempts =
                tracker.redactedRequestAttemptLedger(timelineId);

        assertEquals("fallback ok", first.aiMessage().text());
        assertEquals("fallback ok", second.aiMessage().text());
        assertEquals(2, fallbackCalls.get());
        assertEquals(
                "route_supplier_runtime_exception",
                TraceStore.get(
                        "llm.gateway.fallbackAware.routeResolutionFailureReason"));
        assertEquals(
                2L,
                TraceStore.get(
                        "llm.gateway.fallbackAware.routeResolutionFailureCount"));
        assertEquals(2, attempts.size());
        assertTrue(attempts.stream()
                .allMatch(row -> "primary".equals(row.get("role"))));
        assertFalse(TraceStore.getAll().toString().contains(rawRouteFailure));

        TraceStore.put(
                "llm.gateway.fallbackAware.routeResolutionFailureCount",
                Long.MAX_VALUE);
        model.chat(List.<ChatMessage>of(UserMessage.from("probe")));
        assertEquals(
                Long.MAX_VALUE,
                TraceStore.get(
                        "llm.gateway.fallbackAware.routeResolutionFailureCount"));
    }

    @Test
    void routeResolutionFailureCountIsAtomicAcrossSharedTraceContext() throws Exception {
        String countKey =
                "llm.gateway.fallbackAware.routeResolutionFailureCount";
        Thread testThread = Thread.currentThread();
        CyclicBarrier concurrentReads = new CyclicBarrier(2);
        Map<String, Object> sharedContext = new ConcurrentHashMap<>() {
            @Override
            public Object get(Object key) {
                Object current = super.get(key);
                if (countKey.equals(key) && Thread.currentThread() != testThread) {
                    try {
                        concurrentReads.await(2, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new AssertionError("concurrent-count-read-sync-failed", ex);
                    }
                }
                return current;
            }
        };
        AtomicInteger fallbackCalls = new AtomicInteger();
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw new RuntimeException("model not found");
            }
        };
        ChatModel fallback = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                fallbackCalls.incrementAndGet();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("fallback ok"))
                        .build();
            }
        };
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                primary,
                () -> fallback,
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3",
                null,
                null,
                null,
                () -> {
                    throw new IllegalStateException("redacted-route-failure");
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> {
                TraceStore.installContext(sharedContext);
                try {
                    return model.chat(List.<ChatMessage>of(UserMessage.from("probe")));
                } finally {
                    TraceStore.clear();
                }
            });
            var second = executor.submit(() -> {
                TraceStore.installContext(sharedContext);
                try {
                    return model.chat(List.<ChatMessage>of(UserMessage.from("probe")));
                } finally {
                    TraceStore.clear();
                }
            });

            assertEquals("fallback ok", first.get(3, TimeUnit.SECONDS).aiMessage().text());
            assertEquals("fallback ok", second.get(3, TimeUnit.SECONDS).aiMessage().text());
            assertEquals(2, fallbackCalls.get());
            assertEquals(2L, sharedContext.get(countKey));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationRecordsOnlyThePrimaryAttemptAndNeverBuildsFallback() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = activeTimeline(tracker, "cancel-request", "cancel-session");
        AtomicInteger fallbackBuilds = new AtomicInteger();
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw new CancellationException("cancelled by caller");
            }
        };
        ModelRuntimeHealthTracker.RequestAttemptRoute primaryRoute = tracker.redactedRequestAttemptRoute(
                "primary-private", "qwen3:8b-private", "http://127.0.0.1:11435/v1", "openai_chat_completions");
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                primary,
                () -> {
                    fallbackBuilds.incrementAndGet();
                    return primary;
                },
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3",
                tracker,
                timelineId,
                primaryRoute,
                () -> tracker.redactedRequestAttemptRoute(
                        "fallback-private", "fallback-private", "http://127.0.0.1:11434/v1",
                        "openai_chat_completions"));

        assertThrows(CancellationException.class, () -> model.chat(List.of(UserMessage.from("probe"))));
        tracker.recordRequestPhase(timelineId, "terminal", null, null, "cancelled");

        List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(0, fallbackBuilds.get());
        assertEquals(1, attempts.size());
        assertEquals("primary", attempts.get(0).get("role"));
        assertEquals("cancelled", attempts.get(0).get("outcome"));
        assertEquals("cancelled_neutral", attempts.get(0).get("failureClass"));
        assertEquals("cancelled", attempts.get(0).get("terminalClass"));
    }

    @Test
    void fallbackConstructionFailureCannotClaimAFallbackEndpointAttempt() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = activeTimeline(tracker, "build-request", "build-session");
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw new RuntimeException("model not found");
            }
        };
        ModelRuntimeHealthTracker.RequestAttemptRoute primaryRoute = tracker.redactedRequestAttemptRoute(
                "primary-private", "qwen3:8b-private", "http://127.0.0.1:11435/v1", "openai_chat_completions");
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                primary,
                () -> {
                    throw new IllegalStateException("fallback setup failed before invocation");
                },
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "api3",
                tracker,
                timelineId,
                primaryRoute,
                () -> tracker.redactedRequestAttemptRoute(
                        "fallback-private", "fallback-private", "http://127.0.0.1:11434/v1",
                        "openai_chat_completions"));

        assertThrows(IllegalStateException.class, () -> model.chat(List.of(UserMessage.from("probe"))));

        List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, attempts.size());
        assertEquals("primary", attempts.get(0).get("role"));
        assertEquals("failed", attempts.get(0).get("outcome"));
    }

    @Test
    void nativePrimaryRecordsOneLedgerRowPerPhysicalProviderExchange() throws Exception {
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            providerRequests.incrementAndGet();
            byte[] response = "{\"message\":{\"content\":\"OK\"},\"done_reason\":\"stop\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = activeTimeline(tracker, "composition-request-private", "composition-session-private");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        ModelRuntimeHealthTracker.RequestAttemptRoute primaryRoute = tracker.redactedRequestAttemptRoute(
                "primary-private", "qwen3:8b-private", baseUrl, "ollama_native");
        ChatModel nativePrimary = new OllamaNativeChatModel(
                baseUrl,
                "qwen3:8b",
                Duration.ofSeconds(2),
                32,
                0.1d,
                null,
                tracker,
                false);
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                nativePrimary,
                null,
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "unused",
                tracker,
                timelineId,
                primaryRoute,
                null);

        try {
            ChatResponse response = model.chat(List.<ChatMessage>of(UserMessage.from("probe")));
            List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);

            assertEquals("OK", response.aiMessage().text());
            assertEquals(1, providerRequests.get(), "the loopback provider must receive one physical request");
            assertEquals(providerRequests.get(), attempts.size(),
                    "one physical provider exchange must produce exactly one request-scoped ledger row");
            assertEquals(1, attempts.get(0).get("attemptTotal"));
            assertTrue(String.valueOf(attempts.get(0).get("optionsHash")).startsWith("hash:"));
            assertTrue(String.valueOf(attempts.get(0).get("httpRequestBodyHash")).startsWith("sha256:"));
            assertTrue(String.valueOf(attempts.get(0).get("httpResponseBodyHash")).startsWith("sha256:"));
            assertEquals(true, attempts.get(0).get("clientHttpResponseObserved"));

            String publicEvidence = attempts.toString();
            assertFalse(publicEvidence.contains("composition-request-private"));
            assertFalse(publicEvidence.contains("composition-session-private"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unrelatedSameTimelineAttemptCannotSuppressGenericPrimaryRow() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = activeTimeline(
                tracker,
                "overlap-request-private",
                "overlap-session-private");
        ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                "overlap-private",
                "qwen3:8b-private",
                "http://127.0.0.1:11435/v1",
                "openai_chat_completions");
        CountDownLatch primaryEntered = new CountDownLatch(1);
        CountDownLatch allowPrimaryReturn = new CountDownLatch(1);
        ChatModel primary = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                primaryEntered.countDown();
                try {
                    if (!allowPrimaryReturn.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test primary release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test primary interrupted", interrupted);
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("primary ok")).build();
            }
        };
        FallbackAwareChatModel model = new FallbackAwareChatModel(
                primary,
                null,
                new LlmGatewayFailureClassifier(),
                null,
                "local",
                "unused",
                tracker,
                timelineId,
                route,
                null);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            var responseFuture = executor.submit(() -> {
                TraceStore.put(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
                try {
                    return model.chat(List.<ChatMessage>of(UserMessage.from("probe")));
                } finally {
                    TraceStore.clear();
                }
            });
            assertTrue(primaryEntered.await(2, TimeUnit.SECONDS));
            tracker.recordRequestAttempt(
                    timelineId,
                    "fallback",
                    route,
                    "success",
                    "none",
                    "success",
                    1L);
            allowPrimaryReturn.countDown();

            assertEquals("primary ok", responseFuture.get(2, TimeUnit.SECONDS).aiMessage().text());
            List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(2, attempts.size(),
                    "an unrelated same-timeline row must not suppress this delegate's generic row");
            assertEquals(List.of("fallback", "primary"),
                    attempts.stream().map(row -> row.get("role")).toList());
        } finally {
            allowPrimaryReturn.countDown();
            executor.shutdownNow();
        }
    }

    private static String activeTimeline(
            ModelRuntimeHealthTracker tracker,
            String requestId,
            String sessionId) {
        String timelineId = tracker.beginRequestTimeline(requestId, sessionId);
        tracker.recordRequestPhase(timelineId, "dispatch", "llmrouter.light", null, null);
        tracker.recordRequestPhase(timelineId, "pending", "llmrouter.light", null, null);
        tracker.recordRequestSelection(
                timelineId,
                "router",
                "qwen3:8b-private",
                "http://127.0.0.1:11435/v1",
                "openai_chat_completions",
                true);
        TraceStore.put(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        return timelineId;
    }
}
