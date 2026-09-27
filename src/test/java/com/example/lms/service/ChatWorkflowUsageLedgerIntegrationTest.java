package com.example.lms.service;

import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class ChatWorkflowUsageLedgerIntegrationTest {

    @Test
    void responsesEndpointRecoveryPreservesConfiguredCap64() throws Exception {
        assertResponsesEndpointRecoveryCap(64, 64);
    }

    @Test
    void responsesEndpointRecoveryPreservesLowerPolicyCapInsteadOfRawRequest() throws Exception {
        assertResponsesEndpointRecoveryCap(128, 32);
    }

    private static void assertResponsesEndpointRecoveryCap(int requestedCap, int configuredCap) throws Exception {
        TraceStore.clear();
        ChatUsageLedger ledger = new ChatUsageLedger();
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            primaryCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"error\":{\"message\":\"Use /v1/responses\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.createContext("/v1/responses", exchange -> {
            fallbackCalls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"status\":\"completed\",\"output_text\":\"bounded ok\",\"usage\":{"
                    + "\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ChatModel primary = configuredFactory(server.getAddress().getPort()).lcWithTimeout(
                    "gemma4:26b", null, null, null, null, configuredCap, 2, 0);
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("llm.api-key", "loopback-key-value")
                    .withProperty("llm.base-url-openai", baseUrl);
            ChatWorkflow workflow = fallbackWorkflow(ledger);
            ReflectionTestUtils.setField(workflow, "env", environment);
            ReflectionTestUtils.setField(workflow, "keyResolver", new KeyResolver(environment));
            ReflectionTestUtils.setField(workflow, "llmProvider", "ollama");
            ReflectionTestUtils.setField(workflow, "defaultModel", "gemma4:26b");
            ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
            ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 0);
            ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
            ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
            ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", false);
            ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", true);
            String answer = ReflectionTestUtils.invokeMethod(workflow, "callWithRetryReportingSuccess",
                    primary, List.of(UserMessage.from("bounded probe")),
                    ChatRequestDto.builder().message("bounded probe").model("gemma4:26b")
                            .maxTokens(requestedCap).build(),
                    (Consumer<Object>) ignored -> { }, false);
            assertEquals("bounded ok", answer);
            assertEquals(1, primaryCalls.get());
            assertEquals(1, fallbackCalls.get());
            Map<?, ?> payload = new ObjectMapper().readValue(requestBody.get(), Map.class);
            assertEquals(configuredCap, payload.get("max_output_tokens"));
            Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
            assertEquals(2L, number(model, "attempts"));
            assertEquals(2L, number(model, "configuredCapKnownAttemptCount"));
            assertEquals(2L * configuredCap, number(model, "configuredCapSum"));
            assertEquals("explicit", model.get("lastSuccessfulCapState"));
            assertEquals("max_output_tokens", model.get("lastSuccessfulParameterKind"));
            assertEquals(2L, number(model, "providerOutputTokens"));
        } finally {
            server.stop(0);
            TraceStore.clear();
        }
    }

    @Test
    void outerRetryCreatesOneLedgerAttemptPerActualModelInvocation() {
        ChatUsageLedger ledger = new ChatUsageLedger();
        AtomicInteger calls = new AtomicInteger();
        ChatModel retryOnce = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                if (calls.incrementAndGet() == 1) {
                    throw new RuntimeException("provider rate limit exceeded");
                }
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok"))
                        .tokenUsage(new TokenUsage(11, 9, 20))
                        .build();
            }
        };

        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "chatUsageLedger", ledger);
        ReflectionTestUtils.setField(workflow, "llmProvider", "openai");
        ReflectionTestUtils.setField(workflow, "defaultModel", "gpt-usage-test");
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 1);
        ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
        ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
        ReflectionTestUtils.setField(workflow, "llmFastBailoutMinTimeoutHitsWithEvidence", 10);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", false);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", false);

        ChatRequestDto request = ChatRequestDto.builder()
                .message("usage probe")
                .model("gpt-usage-test")
                .maxTokens(64)
                .build();

        String answer = ReflectionTestUtils.invokeMethod(
                workflow,
                "callWithRetryReportingSuccess",
                retryOnce,
                List.of(UserMessage.from("usage probe")),
                request,
                (Consumer<Object>) ignored -> { },
                false);

        assertEquals("ok", answer);
        assertEquals(2, calls.get());
        Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
        assertEquals(2L, number(model, "attempts"));
        assertEquals(0L, number(model, "inFlight"));
        assertEquals(1L, number(model, "responseReceived"));
        assertEquals(1L, number(model, "failedBeforeResponse"));
        assertEquals(1L, number(model, "providerUsageObservedAttemptCount"));
        assertEquals(9L, number(model, "providerOutputTokens"));
        assertEquals(2L, number(model, "normalizedRequestCapObservedCount"));
        assertEquals(64L, number(model, "latestNormalizedRequestCap"));
    }

    @Test
    void unsupportedMaxTokensSelfHealCreatesOneOmittedCapAttempt() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        HttpServer server = jsonServer(
                "/v1/chat/completions",
                "{\"id\":\"count-only\",\"object\":\"chat.completion\",\"created\":1,"
                        + "\"model\":\"gemma4:26b\",\"choices\":[{\"index\":0,\"message\":{"
                        + "\"role\":\"assistant\",\"content\":\"healed ok\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":4,\"total_tokens\":12}}");
        try {
            ChatModel unsupported = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    throw new RuntimeException("unsupported parameter max_tokens; use provider default");
                }
            };
            ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(workflow, "chatUsageLedger", ledger);
            ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory",
                    configuredFactory(server.getAddress().getPort()));
            ReflectionTestUtils.setField(workflow, "llmProvider", "ollama");
            ReflectionTestUtils.setField(workflow, "defaultModel", "gemma4:26b");
            ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
            ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
            ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 0);
            ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
            ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
            ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", false);
            ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", false);

            String answer = ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callWithRetryReportingSuccess",
                    unsupported,
                    List.of(UserMessage.from("usage probe")),
                    null,
                    (Consumer<Object>) ignored -> { },
                    false);

            assertEquals("healed ok", answer);
            Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
            assertEquals(2L, number(model, "attempts"));
            assertEquals(1L, number(model, "failedBeforeResponse"));
            assertEquals(1L, number(model, "responseReceived"));
            assertEquals(2L, number(model, "unknownAttemptCount"));
            assertEquals("omitted", model.get("lastSuccessfulCapState"));
            assertEquals("omitted", model.get("lastSuccessfulParameterKind"));
            assertEquals(4L, number(model, "providerOutputTokens"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void completionsFallbackRecordsItsConfiguredCapAndProviderUsageOnce() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        HttpServer server = jsonServer(
                "/v1/completions",
                "{\"choices\":[{\"text\":\"fallback ok\"}],"
                        + "\"usage\":{\"prompt_tokens\":13,\"completion_tokens\":5,\"total_tokens\":18}}");
        try {
            ChatWorkflow workflow = fallbackWorkflow(ledger);
            ChatRequestDto request = ChatRequestDto.builder().maxTokens(64).build();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

            String answer = ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callCompletionsFallback",
                    "gpt-usage-test",
                    List.of(UserMessage.from("usage probe")),
                    request,
                    baseUrl,
                    false);

            assertEquals("fallback ok", answer);
            Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
            assertEquals(1L, number(model, "attempts"));
            assertEquals(1L, number(model, "responseReceived"));
            assertEquals(1L, number(model, "configuredCapKnownAttemptCount"));
            assertEquals(64L, number(model, "configuredCapSum"));
            assertEquals("max_tokens", model.get("lastSuccessfulParameterKind"));
            assertEquals(13L, number(model, "providerInputTokens"));
            assertEquals(5L, number(model, "providerOutputTokens"));
            assertEquals(18L, number(model, "providerTotalTokens"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void completionsFallbackExpectedFailureMarkerDoesNotBecomeSuccessfulCap() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        HttpServer server = jsonServer(
                "/v1/completions",
                "{\"choices\":[{\"text\":\"code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH\"}]," +
                        "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}");
        try {
            ChatWorkflow workflow = fallbackWorkflow(ledger);
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

            assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callCompletionsFallback",
                    "gpt-usage-test",
                    List.of(UserMessage.from("usage probe")),
                    ChatRequestDto.builder().maxTokens(64).build(),
                    baseUrl,
                    false));

            Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
            assertEquals(1L, number(model, "responseReceived"));
            assertNull(model.get("lastSuccessfulCapState"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responsesFallbackKeepsOmittedCapAndMissingUsageDistinctFromZero() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        HttpServer server = jsonServer(
                "/v1/responses",
                "{\"status\":\"completed\",\"output_text\":\"responses ok\","
                        + "\"usage\":{\"input_tokens\":21,\"output_tokens\":6,\"total_tokens\":27}}");
        try {
            ChatWorkflow workflow = fallbackWorkflow(ledger);
            ChatRequestDto request = ChatRequestDto.builder().maxTokens(64).build();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

            String answer = ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callResponsesFallback",
                    "gpt-usage-test",
                    List.of(UserMessage.from("usage probe")),
                    request,
                    baseUrl,
                    false, null);

            assertEquals("responses ok", answer);
            Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
            assertEquals(1L, number(model, "attempts"));
            assertEquals(1L, number(model, "responseReceived"));
            assertEquals(0L, number(model, "configuredCapKnownAttemptCount"));
            assertEquals(1L, number(model, "unknownAttemptCount"));
            assertEquals("omitted", model.get("lastSuccessfulCapState"));
            assertEquals("omitted", model.get("lastSuccessfulParameterKind"));
            assertEquals(1L, number(model, "providerUsageObservedAttemptCount"));
            assertEquals(0L, number(model, "providerUsageMissingAttemptCount"));
            assertEquals(21L, number(model, "providerInputTokens"));
            assertEquals(6L, number(model, "providerOutputTokens"));
            assertEquals(27L, number(model, "providerTotalTokens"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responsesFallbackMalformed200DoesNotMarkSuccessfulOrReturnPlaceholder() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        HttpServer server = jsonServer(
                "/v1/responses",
                "{\"usage\":{\"input_tokens\":21,\"output_tokens\":6,\"total_tokens\":27}}");
        try {
            ChatWorkflow workflow = fallbackWorkflow(ledger);
            ChatRequestDto request = ChatRequestDto.builder().maxTokens(64).build();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

            assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callResponsesFallback",
                    "gpt-usage-test",
                    List.of(UserMessage.from("usage probe")),
                    request,
                    baseUrl,
                    false, null));

            Map<String, Object> model = nested(ledger.snapshot(), "modelInvocations");
            assertEquals(1L, number(model, "attempts"));
            assertEquals(1L, number(model, "responseReceived"));
            assertNull(model.get("lastSuccessfulCapState"));
            assertNull(model.get("lastSuccessfulParameterKind"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responsesFallbackAllowsOrdinaryRouteTermInValidAnswer() throws Exception {
        ChatUsageLedger ledger = new ChatUsageLedger();
        HttpServer server = jsonServer(
                "/v1/responses",
                "{\"status\":\"completed\",\"output_text\":\"ROUTE_RESPONSES guide\"," +
                        "\"usage\":{\"input_tokens\":2,\"output_tokens\":3,\"total_tokens\":5}}");
        try {
            ChatWorkflow workflow = fallbackWorkflow(ledger);
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

            String answer = ReflectionTestUtils.invokeMethod(
                    workflow,
                    "callResponsesFallback",
                    "gpt-usage-test",
                    List.of(UserMessage.from("usage probe")),
                    ChatRequestDto.builder().maxTokens(64).build(),
                    baseUrl,
                    false, null);

            assertEquals("ROUTE_RESPONSES guide", answer);
            assertEquals("omitted", nested(ledger.snapshot(), "modelInvocations").get("lastSuccessfulCapState"));
        } finally {
            server.stop(0);
        }
    }

    private static ChatWorkflow fallbackWorkflow(ChatUsageLedger ledger) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OPENAI_API_KEY", "loopback-key-value");
        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "chatUsageLedger", ledger);
        ReflectionTestUtils.setField(workflow, "env", environment);
        ReflectionTestUtils.setField(workflow, "keyResolver", new KeyResolver(environment));
        ReflectionTestUtils.setField(workflow, "openaiWebClient", WebClient.builder().build());
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
        return workflow;
    }

    private static DynamicChatModelFactory configuredFactory(int port) {
        String baseUrl = "http://127.0.0.1:" + port + "/v1";
        MockEnvironment environment = new MockEnvironment().withProperty("llm.api-key", "ollama");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(
                environment,
                new KeyResolver(environment));
        ReflectionTestUtils.setField(factory, "defaultModelName", "gemma4:26b");
        ReflectionTestUtils.setField(factory, "localBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "fastLocalBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "highLocalBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "judgeLocalBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "visionLocalBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "localApiKey", "ollama");
        ReflectionTestUtils.setField(factory, "ownerToken", "");
        ReflectionTestUtils.setField(factory, "allowedHosts", "");
        ReflectionTestUtils.setField(factory, "allowPrivateRemote", false);
        ReflectionTestUtils.setField(factory, "requireAuthForRemote", true);
        ReflectionTestUtils.setField(factory, "dynamicMaxRetries", 0);
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", false);
        return factory;
    }

    private static HttpServer jsonServer(String path, String json) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        return server;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.get(key);
    }

    private static long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }
}
