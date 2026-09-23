package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWorkflowStrictSingleAttemptHttpIntegrationTest {

    @AfterEach
    void clearTrace() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"stream_error_after_partial", "timeout_after_partial",
            "tool_side_effect_started", "context_limit_exceeded", "capability_mismatch", "route_disabled"})
    void outerRetryPreservesGatewayReplayProhibition(String reason) {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        var failure = new com.example.lms.llm.gateway.LlmGatewayException(
                "synthetic gateway failure", com.example.lms.llm.gateway.LlmFailureClass.PROVIDER_ERROR, reason);
        failure.initCause(new RuntimeException("upstream failure after operation"));
        ChatModel primary = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                primaryCalls.incrementAndGet();
                throw failure;
            }
        };
        ChatModel fallback = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                fallbackCalls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("unexpected replay")).build();
            }
        };
        var wrapped = new com.example.lms.llm.gateway.FallbackAwareChatModel(
                primary, () -> fallback, new com.example.lms.llm.gateway.LlmGatewayFailureClassifier(),
                null, "primary", "fallback");

        assertThrows(RuntimeException.class, () -> invokeReplayWorkflow(wrapped));

        assertEquals(1, primaryCalls.get(), "outer workflow must preserve the inner no-replay decision");
        assertEquals(0, fallbackCalls.get());
        assertFalse(com.example.lms.llm.LlmErrorClassifier.classify(failure).retryable());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledRebuildCannotRestoreOriginalModelInEitherStrictMode(boolean strict) {
        AtomicInteger rebuildCalls = new AtomicInteger();
        AtomicInteger originalCalls = new AtomicInteger();
        var failure = new com.example.lms.llm.gateway.LlmGatewayException(
                "synthetic operator OFF", com.example.lms.llm.gateway.LlmFailureClass.DISABLED, "route_disabled");
        DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class, invocation -> {
            if ("lcWithTimeout".equals(invocation.getMethod().getName())) {
                rebuildCalls.incrementAndGet();
                throw failure;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        ChatModel original = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                originalCalls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("unexpected OFF bypass")).build();
            }
        };
        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", factory);
        ReflectionTestUtils.setField(workflow, "llmProvider", "openai");
        ReflectionTestUtils.setField(workflow, "defaultModel", "gpt-disabled-stub");
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 3);
        ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
        ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", true);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", true);

        var thrown = assertThrows(com.example.lms.llm.gateway.LlmGatewayException.class,
                () -> ReflectionTestUtils.invokeMethod(workflow, "callWithRetryReportingSuccess", original,
                        List.of(UserMessage.from("synthetic OFF probe")),
                        ChatRequestDto.builder().message("synthetic OFF probe").model("gpt-disabled-stub").maxTokens(64).build(),
                        (Consumer<Object>) ignored -> { }, strict));

        org.junit.jupiter.api.Assertions.assertSame(failure, thrown);
        assertEquals(1, rebuildCalls.get());
        assertEquals(0, originalCalls.get());
        assertNull(TraceStore.get("llm.endpoint.compat.mismatch"));
        assertNull(TraceStore.get(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY));
    }

    @Test
    void partialOutputCannotTriggerEndpointCompatibilityRepair() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                calls.incrementAndGet();
                throw new com.example.lms.llm.gateway.LlmGatewayException(
                        "This is not a chat model and thus not supported in the v1/chat/completions endpoint. Did you mean to use v1/completions?",
                        com.example.lms.llm.gateway.LlmFailureClass.STREAM_ERROR,
                        "stream_error_after_partial");
            }
        };
        assertThrows(RuntimeException.class, () -> invokeReplayWorkflow(model));
        assertEquals(1, calls.get());
        assertNull(TraceStore.get("llm.endpoint.compat.mismatch"),
                "endpoint repair must not run after partial output");
    }

    @Test
    void safeFailureBeforeAnyOutputCanStillRetry() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("upstream temporarily unavailable");
                return ChatResponse.builder().aiMessage(AiMessage.from("safe retry complete")).build();
            }
        };
        assertEquals("safe retry complete", invokeReplayWorkflow(model));
        assertEquals(2, calls.get());
    }

    @ParameterizedTest
    @CsvSource({"chat,free_limit_reached", "responses,free_limit_reached",
            "chat,free_tier_requires_payment", "responses,free_tier_requires_payment",
            "chat,key_daily_cap", "responses,key_daily_cap",
            "chat,insufficient_credits", "responses,insufficient_credits"})
    void quotaHttpFailureCannotReplayThroughFallbackOrOuterRetry(String transport, String prefix) throws Exception {
        HttpReplayProbe probe = runHttpReplayProbe(transport, "insufficient_quota", prefix, true);
        assertEquals(1, probe.primaryCalls(), "quota must issue exactly one primary HTTP request");
        assertEquals(0, probe.fallbackCalls(), "quota must not issue any fallback HTTP request");
        assertNotNull(probe.failure());
        assertFalse(com.example.lms.llm.LlmErrorClassifier.classify(probe.failure()).retryable());
    }

    @ParameterizedTest
    @CsvSource({"chat,unavailable_route,false", "responses,unavailable_route,false",
            "chat,gateway_overloaded,false", "responses,gateway_overloaded,false",
            "chat,unavailable_route,true", "responses,unavailable_route,true",
            "chat,gateway_overloaded,true", "responses,gateway_overloaded,true"})
    void transient429RetainsBoundedHttpRetryAndFallback(String transport, String code, boolean fallback) throws Exception {
        HttpReplayProbe probe = runHttpReplayProbe(transport, code, "synthetic transient limit", fallback);
        assertNull(probe.failure());
        assertEquals(fallback ? 1 : 2, probe.primaryCalls());
        assertEquals(fallback ? 1 : 0, probe.fallbackCalls());
        assertEquals(fallback ? "fallback complete" : "transient retry complete", probe.result());
    }

    private record HttpReplayProbe(int primaryCalls, int fallbackCalls, String result, RuntimeException failure) { }

    private static HttpReplayProbe runHttpReplayProbe(
            String transport, String code, String prefix, boolean useFallback) throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        boolean responses = "responses".equals(transport);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(responses ? "/v1/responses" : "/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = primaryCalls.incrementAndGet();
            boolean fail = call == 1 || "insufficient_quota".equals(code);
            String success = responses ? "{\"status\":\"completed\",\"output_text\":\"transient retry complete\"}"
                    : chatSuccessBody("transient retry complete");
            byte[] body = (fail ? "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + prefix + "\"}}"
                    : success).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(fail ? 429 : 200, body.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/fallback/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            fallbackCalls.incrementAndGet();
            byte[] body = chatSuccessBody("fallback complete").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            MockEnvironment env = new MockEnvironment().withProperty("OPENAI_API_KEY", "loopback-key-value");
            DynamicChatModelFactory factory = new DynamicChatModelFactory(env, new KeyResolver(env));
            ReflectionTestUtils.setField(factory, "openAiBaseUrl", baseUrl + "/v1");
            // Keep workflow retries enabled while isolating the existing SDK retry control.
            ChatModel primary = factory.lcWithTimeout("gpt-replay-stub", 1.0d, 1.0d, null, null, 64, 2, 0);
            if (responses) {
                ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
                primary = new ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel(
                        baseUrl + "/v1", "loopback-key-value", "gpt-replay-stub", 5_000L, tracker, "primary",
                        tracker.redactedRequestAttemptRoute("quota-primary", "gpt-replay-stub",
                                baseUrl + "/v1/responses", "openai_responses"));
            }
            DynamicChatModelFactory fallbackFactory = new DynamicChatModelFactory(env, new KeyResolver(env));
            ReflectionTestUtils.setField(fallbackFactory, "openAiBaseUrl", baseUrl + "/fallback/v1");
            ChatModel fallback = fallbackFactory.lcWithTimeout("gpt-replay-stub", 1.0d, 1.0d, null, null, 64, 2, 0);
            ChatModel model = useFallback ? new com.example.lms.llm.gateway.FallbackAwareChatModel(
                    primary, () -> fallback, new com.example.lms.llm.gateway.LlmGatewayFailureClassifier(),
                    null, "quota-primary", "quota-fallback") : primary;
            String result = null;
            RuntimeException failure = null;
            try { result = invokeReplayWorkflow(model); }
            catch (RuntimeException caught) { failure = caught; }
            System.out.printf("quota_http transport=%s code=%s prefix=%s fallbackEnabled=%s primary=%d fallback=%d%n",
                    transport, code, prefix, useFallback, primaryCalls.get(), fallbackCalls.get());
            return new HttpReplayProbe(primaryCalls.get(), fallbackCalls.get(), result, failure);
        } finally {
            server.stop(0);
        }
    }

    private static String chatSuccessBody(String text) {
        return "{\"id\":\"loopback\",\"object\":\"chat.completion\",\"model\":\"gpt-replay-stub\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + text
                + "\"},\"finish_reason\":\"stop\"}]}";
    }

    private static String invokeReplayWorkflow(ChatModel model) {
        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "llmProvider", "openai");
        ReflectionTestUtils.setField(workflow, "defaultModel", "gpt-replay-stub");
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 3);
        ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
        ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
        ReflectionTestUtils.setField(workflow, "llmFastBailoutMinTimeoutHitsWithEvidence", 10);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", false);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", false);
        return ReflectionTestUtils.invokeMethod(workflow, "callWithRetryReportingSuccess", model,
                List.of(UserMessage.from("bounded replay probe")),
                ChatRequestDto.builder().message("bounded replay probe").model("gpt-replay-stub").maxTokens(64).build(),
                (Consumer<Object>) ignored -> { }, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"caller", "process_500", "process_sampling", "process_model"})
    void strictPrimaryRemainsOnePhysicalAttemptWhenDynamicRetriesAreConfigured(String mode) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            String message = switch (mode) {
                case "process_sampling" -> "Unsupported parameter: 'temperature'";
                case "process_model" -> "model is required";
                default -> "upstream unavailable";
            };
            byte[] response = ("{\"error\":{\"message\":\"" + message + "\",\"type\":\"server_error\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(mode.equals("process_sampling") || mode.equals("process_model") ? 400 : 500, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            MockEnvironment env = new MockEnvironment().withProperty("OPENAI_API_KEY", "loopback-key-value");
            DynamicChatModelFactory factory = new DynamicChatModelFactory(env, new KeyResolver(env));
            ReflectionTestUtils.setField(factory, "openAiBaseUrl", baseUrl);
            ReflectionTestUtils.setField(factory, "dynamicMaxRetries", 2);
            ChatModel initialModel = factory.lcWithTimeout(
                    "gpt-primary-loopback", 1.0d, 1.0d, null, null, 64, 2);

            ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", factory);
            ReflectionTestUtils.setField(workflow, "llmStrictSingleAttempt", !mode.equals("caller"));
            ReflectionTestUtils.setField(workflow, "llmProvider", "openai");
            ReflectionTestUtils.setField(workflow, "defaultModel", "gpt-primary-loopback");
            ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
            ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
            ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 3);
            ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
            ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
            ReflectionTestUtils.setField(workflow, "llmFastBailoutMinTimeoutHitsWithEvidence", 1);
            ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", true);
            ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", true);

            ChatRequestDto request = ChatRequestDto.builder()
                    .message("bounded primary probe")
                    .model("gpt-primary-loopback")
                    .maxTokens(64)
                    .build();

            assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callWithRetryReportingSuccess",
                    initialModel,
                    List.of(UserMessage.from("bounded primary probe")),
                    request,
                    (Consumer<Object>) ignored -> { },
                    mode.equals("caller")));

            assertEquals(1, requests.get(), "strict primary must cap hidden client retries as well as workflow retries");
            assertEquals(1, TraceStore.get("llm.call.maxAttempts"));
            assertEquals(true, TraceStore.get("llm.call.strictSingleAttempt"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void completionsFallbackRecordsOneApplicationOnlyRowPerPhysicalCallInOrder() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = requests.incrementAndGet();
            int status = call == 1 ? 200 : 503;
            byte[] response = (call == 1
                    ? "{\"choices\":[{\"text\":\"completion ok\"}]}"
                    : "{\"error\":{\"message\":\"private upstream error\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timelineId = tracker.beginRequestTimeline("completions-request", "completions-session");
            tracker.recordRequestPhase(timelineId, "dispatch", "gpt-completions", null, "none");
            tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
            TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
            MockEnvironment env = new MockEnvironment().withProperty("OPENAI_API_KEY", "loopback-key-value");
            ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(workflow, "env", env);
            ReflectionTestUtils.setField(workflow, "keyResolver", new KeyResolver(env));
            ReflectionTestUtils.setField(workflow, "openaiWebClient", WebClient.builder().build());
            ReflectionTestUtils.setField(workflow, "modelRuntimeHealthTracker", tracker);
            ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            List<ChatMessage> messages = List.of(UserMessage.from("completions probe"));
            ChatRequestDto dto = ChatRequestDto.builder()
                    .temperature(0.2d)
                    .topP(0.8d)
                    .maxTokens(64)
                    .build();

            assertEquals("completion ok", ReflectionTestUtils.invokeMethod(
                    workflow, "callCompletionsFallback", "gpt-completions", messages, dto, baseUrl, false));
            assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(
                    workflow, "callCompletionsFallback", "gpt-completions", messages, dto, baseUrl, false));

            assertEquals(2, requests.get());
            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(2, rows.size());
            assertEquals(List.of("success", "failed"), rows.stream().map(row -> row.get("outcome")).toList());
            for (Map<String, Object> row : rows) {
                assertEquals("openai_completions", row.get("protocol"));
                assertEquals(13, row.get("optionItemCount"));
                assertEquals("hash:unknown", row.get("httpRequestBodyHash"));
                assertEquals("hash:unknown", row.get("httpResponseBodyHash"));
                assertEquals(false, row.get("clientHttpExchangeObserved"));
                assertEquals(false, row.get("clientHttpResponseObserved"));
                assertEquals(false, row.get("providerAttemptObserved"));
                assertEquals(false, row.get("wireAttemptObserved"));
            }
            assertEquals(true, rows.get(0).get("responseObserved"));
            assertEquals(false, rows.get(1).get("responseObserved"));
            assertFalse(String.valueOf(rows).contains("completions probe"));
            assertFalse(String.valueOf(rows).contains("private upstream error"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void completionsFallbackInsidePrimaryContextKeepsItsOwnExactOptionEnvelope() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] response = "{\"choices\":[{\"text\":\"fallback context ok\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timelineId = tracker.beginRequestTimeline("context-completions", "context-session");
            tracker.recordRequestPhase(timelineId, "dispatch", "primary-model", null, "none");
            tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
            TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
            MockEnvironment env = new MockEnvironment().withProperty("OPENAI_API_KEY", "loopback-key-value");
            ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(workflow, "env", env);
            ReflectionTestUtils.setField(workflow, "keyResolver", new KeyResolver(env));
            ReflectionTestUtils.setField(workflow, "openaiWebClient", WebClient.builder().build());
            ReflectionTestUtils.setField(workflow, "modelRuntimeHealthTracker", tracker);
            ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            List<ChatMessage> messages = List.of(UserMessage.from("context completions probe"));
            ChatRequestDto dto = ChatRequestDto.builder().temperature(0.2d).topP(0.8d).maxTokens(64).build();
            Map<String, Object> primaryOptions = ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                    "openai", "primary-model", "openai_chat_completions",
                    Map.of("timeoutMs", 9_000L, "maxRetries", 0));
            ChatModel fallbackDelegate = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> ignored) {
                    String answer = ReflectionTestUtils.invokeMethod(
                            workflow, "callCompletionsFallback", "gpt-completions", messages, dto, baseUrl, false);
                    return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
                }
            };

            tracker.decorateRequestAttempt(
                            fallbackDelegate,
                            "primary",
                            tracker.redactedRequestAttemptRoute(
                                    "primary-route", "primary-model", baseUrl, "openai_chat_completions"),
                            primaryOptions)
                    .chat(messages);

            assertEquals(1, requests.get());
            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, rows.size());
            Map<String, Object> row = rows.get(0);
            Map<String, Object> owned = new java.util.LinkedHashMap<>();
            owned.put("temperature", 0.2d);
            owned.put("topP", 0.8d);
            owned.put("frequencyPenalty", 0.0d);
            owned.put("presencePenalty", 0.0d);
            owned.put("maxTokens", 64);
            owned.put("timeoutMs", 2_000L);
            owned.put("maxRetries", 0);
            owned.put("fallbackEnabled", true);
            owned.put("fallbackKey", "completions");
            Map<String, Object> fallbackOptions = ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                    "openai", "gpt-completions", "openai_completions", owned);
            assertEquals(optionHash(fallbackOptions), row.get("optionsHash"));
            assertFalse(optionHash(primaryOptions).equals(row.get("optionsHash")));
            assertEquals("fallback", row.get("role"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responsesFallbackMissingKeyRecordsOneDisabledNoOutboundRowBeforeConstruction() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("responses-missing-key", "responses-missing-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "gpt-5-pro", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        MockEnvironment env = new MockEnvironment();
        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "env", env);
        ReflectionTestUtils.setField(workflow, "keyResolver", new KeyResolver(env));
        ReflectionTestUtils.setField(workflow, "modelRuntimeHealthTracker", tracker);
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);

        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                workflow,
                "callResponsesFallback",
                "gpt-5-pro",
                List.of(UserMessage.from("responses missing key probe")),
                ChatRequestDto.builder().maxTokens(64).build(),
                "https://api.openai.com/v1",
                false, null));

        List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("failed", row.get("outcome"));
        assertEquals("disabled", row.get("failureClass"));
        assertEquals("configuration_error", row.get("terminalClass"));
        assertEquals("hash:unknown", row.get("responseHash"));
        assertEquals(0, row.get("responseCharCount"));
        assertEquals(false, row.get("responseObserved"));
        assertEquals(false, row.get("modelAdapterAttemptObserved"));
        assertEquals(false, row.get("clientHttpExchangeObserved"));
        assertEquals(false, row.get("clientHttpResponseObserved"));
        assertEquals(false, row.get("providerAttemptObserved"));
        assertEquals(false, row.get("wireAttemptObserved"));
    }

    private static String optionHash(Map<String, Object> options) {
        try {
            String json = new ObjectMapper()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(options);
            return SafeRedactor.hashValue(json);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void strictPrimaryDoesNotInvokeRetryCapableModelWhenZeroRetryRebuildIsRejected() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] response = "{\"error\":{\"message\":\"upstream unavailable\",\"type\":\"server_error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            MockEnvironment env = new MockEnvironment().withProperty("OPENAI_API_KEY", "loopback-key-value");
            DynamicChatModelFactory initialFactory = new DynamicChatModelFactory(env, new KeyResolver(env));
            ReflectionTestUtils.setField(initialFactory, "openAiBaseUrl", baseUrl);
            ReflectionTestUtils.setField(initialFactory, "dynamicMaxRetries", 2);
            ChatModel retryingInitialModel = initialFactory.lcWithTimeout(
                    "gpt-rebuild-guard-loopback", 1.0d, 1.0d, null, null, 64, 2);

            DynamicChatModelFactory rejectingFactory = mock(DynamicChatModelFactory.class);
            when(rejectingFactory.lcWithTimeout(
                    anyString(),
                    nullable(Double.class),
                    nullable(Double.class),
                    nullable(Double.class),
                    nullable(Double.class),
                    nullable(Integer.class),
                    anyInt(),
                    eq(0)))
                    .thenThrow(new IllegalStateException("forced zero-retry rebuild rejection"));

            ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", rejectingFactory);
            ReflectionTestUtils.setField(workflow, "llmProvider", "openai");
            ReflectionTestUtils.setField(workflow, "defaultModel", "gpt-rebuild-guard-loopback");
            ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
            ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
            ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 3);
            ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
            ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
            ReflectionTestUtils.setField(workflow, "llmFastBailoutMinTimeoutHitsWithEvidence", 1);
            ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", true);
            ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", true);

            ChatRequestDto request = ChatRequestDto.builder()
                    .message("rejected zero-retry rebuild probe")
                    .model("gpt-rebuild-guard-loopback")
                    .maxTokens(64)
                    .build();

            assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callWithRetryReportingSuccess",
                    retryingInitialModel,
                    List.of(UserMessage.from("rejected zero-retry rebuild probe")),
                    request,
                    (Consumer<Object>) ignored -> { },
                    true));

            assertEquals(0, requests.get(),
                    "strict primary must fail closed instead of reusing a retry-capable model");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestBudgetTimeoutDoesNotPolluteModelHealthFailureBookkeeping() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        ChatModel slowProvider = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                requests.incrementAndGet();
                try {
                    Thread.sleep(750L);
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("late")).build();
            }
        };

        try {
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(workflow, "modelRuntimeHealthTracker", tracker);
            ReflectionTestUtils.setField(workflow, "llmProvider", "openai");
            ReflectionTestUtils.setField(workflow, "defaultModel", "gpt-budget-loopback");
            ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
            ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
            ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 0);
            ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
            ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
            ReflectionTestUtils.setField(workflow, "llmFastBailoutMinTimeoutHitsWithEvidence", 1);
            ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", false);
            ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", false);

            ChatRequestDto request = ChatRequestDto.builder()
                    .message("bounded slow-provider probe")
                    .model("gpt-budget-loopback")
                    .maxTokens(64)
                    .build();

            TimeBudgetContext.set(new TimeBudget(300L));
            assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callWithRetryReportingSuccess",
                    slowProvider,
                    List.of(UserMessage.from("bounded slow-provider probe")),
                    request,
                    (Consumer<Object>) ignored -> { },
                    true));

            assertEquals(1, requests.get(), "the slow provider should receive one physical request");
            assertEquals(Boolean.TRUE, TraceStore.get("llm.requestBudget.timeout.capped"));
            assertEquals("chat_draft", TraceStore.get("llm.requestBudget.timeout.stage"));
            assertTrue(tracker.snapshot("openai", "gpt-budget-loopback").isEmpty(),
                    "request-budget exhaustion must not count as a provider model failure");
            assertEquals("request_budget_exhausted", TraceStore.get("llm.final.skipped"));
        } finally {
            TimeBudgetContext.clear();
        }
    }

    @Test
    void providerOwnedTimeoutBeforeGenerousRequestDeadlineStillRecordsFailure() {
        AtomicInteger requests = new AtomicInteger();
        ChatModel slowProvider = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                requests.incrementAndGet();
                try {
                    Thread.sleep(1_500L);
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("late")).build();
            }
        };

        ModelRuntimeHealthTracker tracker = mock(ModelRuntimeHealthTracker.class);
        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "modelRuntimeHealthTracker", tracker);
        ReflectionTestUtils.setField(workflow, "llmProvider", "openai");
        ReflectionTestUtils.setField(workflow, "defaultModel", "gpt-provider-loopback");
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 1);
        ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 1);
        ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 0);
        ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
        ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
        ReflectionTestUtils.setField(workflow, "llmFastBailoutMinTimeoutHitsWithEvidence", 1);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", false);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", false);

        ChatRequestDto request = ChatRequestDto.builder()
                .message("provider-owned timeout probe")
                .model("gpt-provider-loopback")
                .maxTokens(64)
                .build();

        try {
            TimeBudgetContext.set(new TimeBudget(5_000L));
            assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callWithRetryReportingSuccess",
                    slowProvider,
                    List.of(UserMessage.from("provider-owned timeout probe")),
                    request,
                    (Consumer<Object>) ignored -> { },
                    true));

            assertEquals(1, requests.get());
            assertEquals(Boolean.FALSE, TraceStore.get("llm.requestBudget.timeout.capped"));
            assertNull(TraceStore.get("llm.final.skipped"));
            verify(tracker).recordCurrentRequestRouteFailure(
                    "gpt-provider-loopback", "timeout");
        } finally {
            TimeBudgetContext.clear();
        }
    }

    @Test
    void providerThrownTimeoutWhileRequestCapStillHasTimeRecordsProviderFailure() {
        AtomicInteger requests = new AtomicInteger();
        ChatModel providerTimeout = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                requests.incrementAndGet();
                throw new RuntimeException(new TimeoutException("provider-owned immediate timeout"));
            }
        };

        ModelRuntimeHealthTracker tracker = mock(ModelRuntimeHealthTracker.class);
        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "modelRuntimeHealthTracker", tracker);
        ReflectionTestUtils.setField(workflow, "llmProvider", "openai");
        ReflectionTestUtils.setField(workflow, "defaultModel", "gpt-provider-capped-loopback");
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 10);
        ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 10);
        ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 0);
        ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
        ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 10_000L);
        ReflectionTestUtils.setField(workflow, "llmFastBailoutMinTimeoutHitsWithEvidence", 1);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", false);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", false);

        ChatRequestDto request = ChatRequestDto.builder()
                .message("provider-owned capped-timeout probe")
                .model("gpt-provider-capped-loopback")
                .maxTokens(64)
                .build();

        try {
            TimeBudgetContext.set(new TimeBudget(5_000L));
            assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callWithRetryReportingSuccess",
                    providerTimeout,
                    List.of(UserMessage.from("provider-owned capped-timeout probe")),
                    request,
                    (Consumer<Object>) ignored -> { },
                    true));

            assertEquals(1, requests.get());
            assertEquals(Boolean.TRUE, TraceStore.get("llm.requestBudget.timeout.capped"));
            assertNull(TraceStore.get("llm.final.skipped"));
            verify(tracker).recordCurrentRequestRouteFailure(
                    "gpt-provider-capped-loopback", "timeout");
        } finally {
            TimeBudgetContext.clear();
        }
    }
}
