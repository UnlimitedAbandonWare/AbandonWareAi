package com.example.lms.llm;

import ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatBaseUrlContractTest {
    @ParameterizedTest
    @CsvSource({
        "https://api.openai.com,https://api.openai.com/v1",
        "http://localhost:11434/,http://localhost:11434/v1",
        "https://example.test/v1/,https://example.test/v1",
        "https://example.test/v1beta/openai/,https://example.test/v1beta/openai",
        "https://example.test/openai/v1,https://example.test/openai/v1",
        "https://example.test/proxy/v1/tenant,https://example.test/proxy/v1/tenant",
        "https://example.test/proxy/v1/tenant/chat/completions,https://example.test/proxy/v1/tenant",
        "https://example.test/proxy/responses,https://example.test/proxy",
        "https://example.test/proxy/embeddings,https://example.test/proxy",
        "https://example.test/proxy/completions,https://example.test/proxy"
    }) void preservesBaseAndRemovesOnlyEndpointSuffix(String input, String expected) {
        assertEquals(expected, OpenAiCompatBaseUrl.sanitize(input));
    }
    @ParameterizedTest
    @ValueSource(strings = {"https://user:private@example.test/v1", "ftp://example.test/v1",
            "https://example.test/v1?unsafe=true", "https://example.test/v1#private", "not a URL"})
    void rejectsAmbiguousOrCredentialBearingBaseWithoutEcho(String input) {
        var failure = assertThrows(IllegalArgumentException.class, () -> OpenAiCompatBaseUrl.sanitize(input));
        assertFalse(failure.toString().contains(input));
    }
    @ParameterizedTest
    @ValueSource(strings = {"/v1beta/openai", "/openai/v1", "/proxy/v1/tenant"})
    void responsesActuallyUsesPreservedPrefix(String prefix) throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getRawPath()); exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"status\":\"completed\",\"output_text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try {
            var model = new OpenAiResponsesChatModel("http://127.0.0.1:" + server.getAddress().getPort() + prefix,
                    "fixture", "gpt-5-pro", 5000);
            assertEquals("ok", model.chat(List.of(UserMessage.from("fixture"))).aiMessage().text());
            assertEquals(prefix + "/responses", path.get());
        } finally { server.stop(0); }
    }
    @Test void absentBaseRemainsAbsent() {
        assertEquals("", OpenAiCompatBaseUrl.sanitize(null));
        assertEquals("", OpenAiCompatBaseUrl.sanitize("  "));
    }
    @ParameterizedTest
    @ValueSource(strings = {"/v1beta/openai", "/openai/v1", "/proxy/v1/tenant"})
    void routerUsesSameBaseContract(String prefix) {
        String base = "http://127.0.0.1:12345" + prefix;
        assertEquals(base, org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                ai.abandonware.nova.orch.aop.LlmRouterAspect.class, "normalizeBaseUrl", base));
    }
}
