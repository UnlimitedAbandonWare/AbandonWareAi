package com.example.lms.learning.gemini;

import com.example.lms.guard.ProviderCredentialResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ChatGeminiAdapterFocusedTest {
    @ParameterizedTest
    @ValueSource(strings = {"gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash"})
    void inheritedLocalOptionsRespectGeminiWireContract(String modelName) throws Exception {
        var calls = new AtomicInteger();
        var body = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/openai/chat/completions", exchange -> {
            calls.incrementAndGet();
            JsonNode payload = new ObjectMapper().readTree(exchange.getRequestBody());
            body.set(payload);
            boolean rejected = modelName.equals("gemini-3.5-flash-lite") && payload.has("frequency_penalty");
            byte[] response = (rejected
                    ? "{\"error\":{\"message\":\"frequency_penalty rejected\",\"code\":400}}"
                    : "{\"model\":\"" + modelName + "\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"READY\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(rejected ? 400 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var env = new MockEnvironment().withProperty("GEMINI_API_KEY", "synthetic-gemini-key")
                    .withProperty("gemini.gateway.enabled", "true")
                    .withProperty("gemini.gateway.purpose.router.enabled", "true")
                    .withProperty("gemini.gateway.max-attempts", "1");
            var gateway = new GeminiGateway(WebClient.builder(), new ProviderCredentialResolver(env), env);
            var model = gateway.buildOpenAiCompatibleChatModel(new GeminiGateway.RouterSpec(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/openai", modelName,
                    Duration.ofSeconds(2), 0, 0.8, 0.85, 0.0, 0.0, 64), false);
            var response = model.chat(List.of(SystemMessage.from("Synthetic system"),
                    SystemMessage.from("Synthetic context"), UserMessage.from("Reply READY")));
            assertEquals("READY", response.aiMessage().text());
            assertEquals(1, calls.get());
            assertEquals(modelName, body.get().path("model").asText());
            assertEquals(64, body.get().path("max_tokens").asInt());
            assertFalse(body.get().has("reasoning_effort"));
            assertFalse(body.get().has("response_format"));
            if (modelName.equals("gemini-3.8-flash")) {
                for (String field : List.of("temperature", "top_p", "frequency_penalty", "presence_penalty"))
                    assertFalse(body.get().has(field), field);
            } else {
                assertEquals(0.8, body.get().path("temperature").asDouble());
                assertEquals(0.85, body.get().path("top_p").asDouble());
                assertEquals(0.0, body.get().path("presence_penalty").asDouble());
                assertTrue(body.get().has("presence_penalty"));
                assertEquals(modelName.equals("gemini-2.5-flash"), body.get().has("frequency_penalty"));
            }
        } finally { server.stop(0); }
    }
}
