package com.example.lms.llm;

import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaNativeFailurePayloadTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void http200ErrorObjectIsADeviceLossFailureInsteadOfBlankAssistantSuccess() throws Exception {
        String privateMarker = "private-device-error-marker";
        try (StubServer stub = stub(200,
                "{\"error\":\"invalid main_gpu selection (available devices: 0) "
                        + privateMarker + "\"}")) {
            OllamaNativeChatModel model = model(stub, new ModelRuntimeHealthTracker());

            LlmGatewayException failure = assertThrows(
                    LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("bounded probe"))));

            assertEquals(LlmFailureClass.GPU_DEVICE_LOST, failure.failureClass());
            assertEquals("gpu_device_lost", failure.reasonCode());
            assertEquals(1, stub.calls());
            assertFalse(String.valueOf(TraceStore.getAll()).contains(privateMarker));
        }
    }

    @Test
    void malformedHttp200JsonIsATypedProviderFailureInsteadOfBlankAssistantSuccess() throws Exception {
        try (StubServer stub = stub(200, "{not-json")) {
            OllamaNativeChatModel model = model(stub, new ModelRuntimeHealthTracker());

            LlmGatewayException failure = assertThrows(
                    LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("bounded probe"))));

            assertEquals(LlmFailureClass.PROVIDER_ERROR, failure.failureClass());
            assertEquals("malformed_response", failure.reasonCode());
            assertEquals(1, stub.calls());
        }
    }

    @Test
    void blankHttp200ContentIsATypedProviderFailureInsteadOfACompletedAnswer() throws Exception {
        try (StubServer stub = stub(200,
                "{\"message\":{\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}")) {
            OllamaNativeChatModel model = model(stub, new ModelRuntimeHealthTracker());

            LlmGatewayException failure = assertThrows(
                    LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("bounded probe"))));

            assertEquals(LlmFailureClass.PROVIDER_ERROR, failure.failureClass());
            assertEquals("blank_response", failure.reasonCode());
            assertEquals(1, stub.calls());
        }
    }

    @Test
    void validHttp200ContentStillCompletesOnce() throws Exception {
        try (StubServer stub = stub(200,
                "{\"message\":{\"content\":\"READY\"},\"done\":true,\"done_reason\":\"stop\"}")) {
            OllamaNativeChatModel model = model(stub, new ModelRuntimeHealthTracker());

            ChatResponse response = model.chat(List.of(UserMessage.from("bounded probe")));

            assertEquals("READY", response.aiMessage().text());
            assertEquals(1, stub.calls());
        }
    }

    @Test
    void structuredHttp200ErrorObjectKeepsGpuLossClassification() throws Exception {
        try (StubServer stub = stub(200,
                "{\"error\":{\"message\":\"GPU is lost\",\"code\":\"NVML_ERROR_GPU_IS_LOST\"}}")) {
            OllamaNativeChatModel model = model(stub, new ModelRuntimeHealthTracker());

            LlmGatewayException failure = assertThrows(
                    LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("bounded probe"))));

            assertEquals(LlmFailureClass.GPU_DEVICE_LOST, failure.failureClass());
            assertEquals("gpu_device_lost", failure.reasonCode());
            assertEquals(1, stub.calls());
        }
    }

    private static OllamaNativeChatModel model(StubServer stub, ModelRuntimeHealthTracker tracker) {
        return new OllamaNativeChatModel(
                stub.baseUrl(), "qwen3:8b", Duration.ofSeconds(2), 16, 0.0d,
                null, tracker, false);
    }

    private static StubServer stub(int status, String body) throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return new StubServer(server, calls);
    }

    private record StubServer(HttpServer server, AtomicInteger callCount) implements AutoCloseable {
        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        int calls() {
            return callCount.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
