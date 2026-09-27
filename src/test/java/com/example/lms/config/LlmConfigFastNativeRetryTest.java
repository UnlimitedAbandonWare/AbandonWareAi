package com.example.lms.config;

import com.example.lms.guard.KeyResolver;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmConfigFastNativeRetryTest {
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, -1, 1})
    void nativeFastBeanHonorsConfiguredRetryAdmission(int maxRetries) throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] response = "{\"error\":\"llama runner process has terminated: exit status 2\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var resolver = new KeyResolver(new MockEnvironment().withProperty("llm.api-key", "ollama"));
            ChatModel fast = config().fastChatModel(
                    baseUrl, resolver, "qwen3:8b", 0.0d, 2L, maxRetries, 32);

            var failure = assertThrows(org.springframework.web.reactive.function.client.WebClientResponseException.class,
                    () -> fast.chat(List.of(UserMessage.from("synthetic fast retry probe"))));

            assertEquals(500, failure.getStatusCode().value());
            assertEquals(maxRetries > 0 ? 2 : 1, calls.get(),
                    "zero or negative fast retries must not hide an additional native CPU request");
            assertEquals(maxRetries > 0 ? Boolean.TRUE : null, TraceStore.get("llm.ollamaNative.cpuRetry.used"));
        } finally {
            server.stop(0);
        }
    }
    private static LlmConfig config() {
        LlmConfig config = new LlmConfig();
        ReflectionTestUtils.setField(config, "ownerToken", "");
        ReflectionTestUtils.setField(config, "ownerTokenHeader", "X-Owner-Token");
        ReflectionTestUtils.setField(config, "allowPrivateRemote", false);
        ReflectionTestUtils.setField(config, "allowedHosts", "");
        ReflectionTestUtils.setField(config, "requireAuthForRemote", true);
        return config;
    }
}
