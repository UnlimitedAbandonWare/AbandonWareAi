package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4j101MultimodalRequestSerializationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void mixedUserMessageSerializesNullUrlImageWithoutPromotingClientEvidenceToWireProof()
            throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            requests.incrementAndGet();
            byte[] response = ("{\"choices\":[{\"index\":0,\"message\":{"
                    + "\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ChatModel dependencyModel = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey("local-test")
                    .modelName("qwen3-vl:8b")
                    .timeout(Duration.ofSeconds(5))
                    .maxRetries(0)
                    .build();

            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timelineId = tracker.beginRequestTimeline(
                    "multimodal-wire-shape-request",
                    "multimodal-wire-shape-session");
            tracker.recordRequestPhase(timelineId, "dispatch", "qwen3-vl:8b", null, "none");
            tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
            TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);

            ChatModel trackedModel = tracker.decorateRequestAttempt(
                    dependencyModel,
                    "primary",
                    tracker.redactedRequestAttemptRoute(
                            "vision-primary",
                            "qwen3-vl:8b",
                            baseUrl,
                            "openai_chat_completions"),
                    Map.of());

            byte[] imageBytes = new byte[] {
                    (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            };
            String encoded = Base64.getEncoder().encodeToString(imageBytes);
            ImageContent imageContent = ImageContent.from(encoded, "image/png");
            assertNull(imageContent.image().url());
            assertEquals(ImageContent.DetailLevel.LOW, imageContent.detailLevel());

            ChatResponse result = trackedModel.chat(List.of(UserMessage.from(
                    TextContent.from("multimodal-wire-shape-fixture"),
                    imageContent)));

            assertEquals("ok", result.aiMessage().text());
            assertEquals(1, requests.get());
            byte[] requestBody = capturedBody.get();
            assertNotNull(requestBody, "the controlled loopback must capture one request body");

            JsonNode request = JSON.readTree(requestBody);
            JsonNode messages = request.path("messages");
            assertTrue(messages.isArray(), "messages must be a JSON array");
            assertEquals(1, messages.size());
            JsonNode user = messages.get(0);
            assertEquals("user", user.path("role").asText());

            JsonNode contents = user.path("content");
            assertTrue(contents.isArray(), "mixed user content must remain an array");
            assertEquals(2, contents.size());
            assertEquals("text", contents.get(0).path("type").asText());
            assertEquals("multimodal-wire-shape-fixture", contents.get(0).path("text").asText());
            assertEquals("image_url", contents.get(1).path("type").asText());

            JsonNode imageUrlNode = contents.get(1).path("image_url");
            assertEquals("low", imageUrlNode.path("detail").asText());
            String serializedUrl = imageUrlNode.path("url").asText();
            String prefix = "data:image/png;base64,";
            assertTrue(serializedUrl.startsWith(prefix),
                    "null-URL Base64 images must serialize as an image/png data URL");
            byte[] serializedImage = Base64.getDecoder().decode(serializedUrl.substring(prefix.length()));
            assertEquals(imageBytes.length, serializedImage.length);
            assertEquals(sha256(imageBytes), sha256(serializedImage));

            List<Map<String, Object>> ledger = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, ledger.size());
            assertEquals("openai_chat_completions", ledger.get(0).get("protocol"));
            assertEquals(Boolean.FALSE, ledger.get(0).get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, ledger.get(0).get("wireAttemptObserved"));
        } finally {
            server.stop(0);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
