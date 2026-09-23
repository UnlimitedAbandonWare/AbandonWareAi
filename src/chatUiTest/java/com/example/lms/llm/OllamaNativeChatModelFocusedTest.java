package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaNativeChatModelFocusedTest {

    @Test
    void httpFiveHundredIsAgentVisibleWithoutRawBody() throws Exception {
        TraceStore.clear();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] body = "runtime capacity exhausted".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    "http://127.0.0.1:" + port + "/v1",
                    "qwen3:8b",
                    java.time.Duration.ofSeconds(5),
                    8,
                    0.1d);

            assertThrows(WebClientResponseException.class,
                    () -> model.chat(List.of(UserMessage.from("ping"))));

            assertEquals(500, TraceStore.get("llm.ollamaNative.httpStatus"));
            assertEquals("ollama_upstream_5xx", TraceStore.get("llm.ollamaNative.failureClass"));
            assertEquals("inspect_ollama_runtime_capacity", TraceStore.get("llm.ollamaNative.nextAction"));
            assertEquals(500, TraceStore.get("llm.localSmoke.operatorAction.upstreamStatus"));
            assertEquals("ollama_upstream_5xx",
                    TraceStore.get("llm.localSmoke.operatorAction.upstreamFailureClass"));
            assertEquals("inspect_ollama_runtime_capacity",
                    TraceStore.get("llm.localSmoke.operatorAction.upstreamNextAction"));
            assertTrue(String.valueOf(TraceStore.get("llm.ollamaNative.responseBodyHash")).startsWith("hash:"));
            assertFalse(String.valueOf(TraceStore.getByPrefix("llm.ollamaNative."))
                    .contains("runtime capacity exhausted"));
        } finally {
            server.stop(0);
            TraceStore.clear();
        }
    }
}
