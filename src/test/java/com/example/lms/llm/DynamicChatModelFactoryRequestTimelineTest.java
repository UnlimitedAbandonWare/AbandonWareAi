package com.example.lms.llm;

import com.example.lms.guard.KeyResolver;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DynamicChatModelFactoryRequestTimelineTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void selectedFastEndpointEnrichesTheExistingRequestTimelineWithSafeHostPort() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("raw-request-id", "raw-session-id");
        tracker.recordRequestPhase(timelineId, "dispatch", "qwen3:8b", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);

        MockEnvironment environment = new MockEnvironment().withProperty("llm.api-key", "ollama");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(
                environment,
                new KeyResolver(environment),
                tracker);
        ReflectionTestUtils.setField(factory, "localBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "fastLocalBaseUrl", "http://127.0.0.1:11435/v1");
        ReflectionTestUtils.setField(factory, "highLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "judgeLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "visionLocalBaseUrl", "http://127.0.0.1:11435/v1");
        ReflectionTestUtils.setField(factory, "localApiKey", "ollama");
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);

        factory.lcWithTimeout("qwen3:8b", null, null, null, null, 64, 5);

        List<Map<String, Object>> rows = tracker.redactedRequestTimeline(timelineId);
        assertEquals(List.of("dispatch", "pending"),
                rows.stream().map(row -> String.valueOf(row.get("phase"))).toList());
        assertEquals("127.0.0.1:11435", rows.get(1).get("endpointLabel"));

        String serialized = new ObjectMapper().writeValueAsString(rows);
        assertFalse(serialized.contains("http://127.0.0.1:11435/v1"));
        assertFalse(serialized.contains("raw-request-id"));
        assertFalse(serialized.contains("raw-session-id"));
        assertFalse(serialized.contains("qwen3:8b"));
    }

    @Test
    void selectedHighEndpointEnrichesItsOwnTimelineWithoutReusingTheFastRoute() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "gemma-request", "gemma-session", "gemma4:26b");
        DynamicChatModelFactory factory = configuredFactory(tracker);
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);

        factory.lcWithTimeout("gemma4:26b", null, null, null, null, 64, 5);

        List<Map<String, Object>> rows = tracker.redactedRequestTimeline(timelineId);
        assertEquals("127.0.0.1:11434", rows.get(1).get("endpointLabel"));
    }

    @Test
    void rejectedEndpointNeverEnrichesTheRequestTimeline() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "rejected-request", "rejected-session", "qwen3:8b");
        DynamicChatModelFactory factory = configuredFactory(tracker);
        ReflectionTestUtils.setField(factory, "fastLocalBaseUrl", "http://192.0.2.10:11435/v1");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);

        assertThrows(IllegalStateException.class,
                () -> factory.lcWithTimeout("qwen3:8b", null, null, null, null, 64, 5));

        List<Map<String, Object>> rows = tracker.redactedRequestTimeline(timelineId);
        assertEquals("unknown", rows.get(1).get("endpointLabel"));
    }

    @Test
    void auxiliaryFactoryCallCannotClaimThePrimaryRequestEndpoint() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "primary-request", "primary-session", "gemma4:26b");
        DynamicChatModelFactory factory = configuredFactory(tracker);

        factory.lcWithTimeout("qwen3:8b", null, null, null, null, 64, 5);
        assertEquals("unknown", tracker.redactedRequestTimeline(timelineId).get(1).get("endpointLabel"));

        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);
        factory.lcWithTimeout("gemma4:26b", null, null, null, null, 64, 5);

        assertEquals("127.0.0.1:11434",
                tracker.redactedRequestTimeline(timelineId).get(1).get("endpointLabel"));
    }

    @Test
    void localGemmaThinkingControlPreservesSdkMultimodalAndOtherModelPayloads() throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(exchange.getRequestBody().readAllBytes());
            calls.incrementAndGet();
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"fixture\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            for (String modelId : List.of("gemma4:26b", "gemma4:31b")) {
                for (boolean enabled : List.of(true, false)) {
                    DynamicChatModelFactory factory = configuredFactory(new ModelRuntimeHealthTracker());
                    ReflectionTestUtils.setField(factory, "localBaseUrl", endpoint);
                    ReflectionTestUtils.setField(factory, "highLocalBaseUrl", endpoint);
                    ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", enabled);
                    ChatModel model = factory.lcWithTimeout(modelId, 0.2d, null, null, null, 64, 5);
                    assertEquals("fixture", model.chat(List.of(UserMessage.from(
                            TextContent.from("synthetic-image-question"),
                            ImageContent.from("iVBORw0KGgo=", "image/png")))).aiMessage().text());
                    var request = new ObjectMapper().readTree(captured.get());
                    boolean expectedControl = enabled && modelId.equals("gemma4:26b");
                    assertEquals(expectedControl, request.has("reasoning_effort"), modelId + "/" + enabled);
                    if (expectedControl) assertEquals("none", request.path("reasoning_effort").asText());
                    assertEquals(modelId, request.path("model").asText());
                    assertEquals(64, request.path("max_tokens").asInt());
                    var contents = request.path("messages").get(0).path("content");
                    assertEquals(2, contents.size());
                    assertEquals("synthetic-image-question", contents.get(0).path("text").asText());
                    assertEquals("image_url", contents.get(1).path("type").asText());
                    assertEquals("data:image/png;base64,iVBORw0KGgo=",
                            contents.get(1).path("image_url").path("url").asText());
                }
            }
            assertEquals(4, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void ordinaryFactoryOpenAiCompatibleReturnRecordsExactlyOneApplicationBoundaryRow() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"factory-result\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timelineId = pendingTimeline(tracker, "factory-request", "factory-session", "gemma4:26b");
            DynamicChatModelFactory factory = configuredFactory(tracker);
            ReflectionTestUtils.setField(factory, "localBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(factory, "highLocalBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", false);

            ChatModel model = factory.lcWithTimeout("gemma4:26b", 0.2d, null, null, null, 64, 5);
            model.chat(List.of(UserMessage.from("factory-fixture")));

            assertEquals(1, calls.get());
            List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, attempts.size());
            assertEquals("openai_chat_completions", attempts.get(0).get("protocol"));
            assertEquals(13, attempts.get(0).get("optionItemCount"));
            assertEquals(Boolean.FALSE, attempts.get(0).get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, attempts.get(0).get("wireAttemptObserved"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fullOptionEnvelopeIsInsertionStableAndProviderModelProtocolSensitive() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("temperature", 0.2d);
        forward.put("timeoutMs", 5_000L);
        forward.put("maxRetries", 0);
        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("maxRetries", 0);
        reverse.put("timeoutMs", 5_000L);
        reverse.put("temperature", 0.2d);

        String base = optionHash(tracker, "openai", "model-a", "openai_chat_completions", forward);
        assertEquals(base, optionHash(tracker, "openai", "model-a", "openai_chat_completions", reverse));
        assertNotEquals(base, optionHash(tracker, "ollama", "model-a", "openai_chat_completions", reverse));
        assertNotEquals(base, optionHash(tracker, "openai", "model-b", "openai_chat_completions", reverse));
        assertNotEquals(base, optionHash(tracker, "openai", "model-a", "openai_responses", reverse));
    }

    private static String optionHash(
            ModelRuntimeHealthTracker tracker,
            String provider,
            String model,
            String protocol,
            Map<String, Object> overrides) {
        String timelineId = pendingTimeline(tracker, provider + model + protocol, "option-session", model);
        ChatModel delegate = new ChatModel() {
            @Override
            public dev.langchain4j.model.chat.response.ChatResponse chat(
                    List<dev.langchain4j.data.message.ChatMessage> messages) {
                return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("ok"))
                        .build();
            }
        };
        tracker.decorateRequestAttempt(
                        delegate,
                        "primary",
                        tracker.redactedRequestAttemptRoute("option-route", model, null, protocol),
                        ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(provider, model, protocol, overrides))
                .chat(List.of(UserMessage.from("option probe")));
        Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
        assertEquals(13, row.get("optionItemCount"));
        return String.valueOf(row.get("optionsHash"));
    }

    private static String pendingTimeline(
            ModelRuntimeHealthTracker tracker,
            String requestId,
            String sessionId,
            String modelId) {
        String timelineId = tracker.beginRequestTimeline(requestId, sessionId);
        tracker.recordRequestPhase(timelineId, "dispatch", modelId, null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        return timelineId;
    }

    private static DynamicChatModelFactory configuredFactory(ModelRuntimeHealthTracker tracker) {
        MockEnvironment environment = new MockEnvironment().withProperty("llm.api-key", "ollama");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(
                environment,
                new KeyResolver(environment),
                tracker);
        ReflectionTestUtils.setField(factory, "localBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "fastLocalBaseUrl", "http://127.0.0.1:11435/v1");
        ReflectionTestUtils.setField(factory, "highLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "judgeLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "visionLocalBaseUrl", "http://127.0.0.1:11435/v1");
        ReflectionTestUtils.setField(factory, "localApiKey", "ollama");
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);
        return factory;
    }
}
