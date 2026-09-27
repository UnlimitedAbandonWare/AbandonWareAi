package com.example.lms.llm;

import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicChatModelFactoryOperationalFallbackContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void configuredTransportRetryZeroKeepsOneOperationalCpuFallbackWithoutRequestOverride() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> firstBody = new AtomicReference<>("");
        AtomicReference<String> secondBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int request = requests.incrementAndGet();
            byte[] response;
            int status;
            if (request == 1) {
                firstBody.set(body);
                status = 500;
                response = "{\"error\":\"llama runner process has terminated: exit status 2\"}"
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                secondBody.set(body);
                status = 200;
                response = "{\"message\":{\"content\":\"OK\"},\"done_reason\":\"stop\"}"
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            DynamicChatModelFactory factory = configuredNativeFactory(baseUrl);

            ChatModel model = factory.lcWithTimeout(
                    "qwen3:8b", null, null, null, null, 32, 2);
            ChatResponse response = model.chat(List.of(UserMessage.from("operational fallback contract probe")));

            assertEquals("OK", response.aiMessage().text());
            assertEquals(2, requests.get(),
                    "transport retry zero must not remove the one operational GPU-to-CPU fallback");
            assertFalse(firstBody.get().contains("\"num_gpu\":0"));
            assertTrue(secondBody.get().contains("\"num_gpu\":0"));
            assertEquals(Boolean.TRUE, TraceStore.get("llm.ollamaNative.cpuRetry.used"));
            assertEquals("runner_terminated_cpu_probe", TraceStore.get("llm.ollamaNative.cpuRetry.reason"));
            assertEquals("cpu_fallback_retry", TraceStore.get("llm.ollamaNative.gpuMode"));

            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("operational fallback contract probe"));
            assertFalse(trace.contains("llama runner process has terminated"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void factoryPropagatesEnforcedEndpointQuarantineToNewNativeModelInstances() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int request = requests.incrementAndGet();
            byte[] response = (request == 1
                    ? "{\"error\":\"main_gpu failed because available devices: 0\"}"
                    : "{\"message\":{\"content\":\"CPU_OK\"},\"done_reason\":\"stop\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(request == 1 ? 500 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            LlmGatewayProperties gatewayProperties = new LlmGatewayProperties();
            gatewayProperties.getLocalDeviceFailover().setEnabled(true);
            gatewayProperties.getLocalDeviceFailover().setEnforcement(
                    LlmGatewayProperties.Enforcement.ENFORCE);
            DynamicChatModelFactory factory = configuredNativeFactory(baseUrl, tracker, gatewayProperties);

            ChatResponse first = factory.lcWithTimeout(
                            "qwen3:8b", null, null, null, null, 32, 2)
                    .chat(List.of(UserMessage.from("first factory endpoint request")));
            assertEquals("CPU_OK", first.aiMessage().text());
            assertEquals(2, requests.get());

            LlmGatewayException blocked = assertThrows(
                    LlmGatewayException.class,
                    () -> factory.lcWithTimeout(
                                    "qwen3:8b", null, null, null, null, 32, 2)
                            .chat(List.of(UserMessage.from("second factory endpoint request"))));
            assertEquals("gpu_device_lost", blocked.reasonCode());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rawModelFactoryPathDoesNotRetryOpenEndpoint() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"unexpected\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            LlmGatewayProperties gatewayProperties = new LlmGatewayProperties();
            gatewayProperties.getLocalDeviceFailover().setEnabled(true);
            gatewayProperties.getLocalDeviceFailover().setEnforcement(
                    LlmGatewayProperties.Enforcement.ENFORCE);
            tracker.recordEndpointDeviceLoss(
                    "local",
                    baseUrl,
                    gatewayProperties.getLocalDeviceFailover().toEndpointQuarantinePolicy(),
                    System.currentTimeMillis());
            DynamicChatModelFactory factory = configuredNativeFactory(
                    baseUrl, tracker, gatewayProperties);
            ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", false);

            LlmGatewayException blocked = assertThrows(
                    LlmGatewayException.class,
                    () -> factory.lcWithTimeout(
                                    "gemma4:26b", null, null, null, null, 32, 2)
                            .chat(List.of(UserMessage.from("raw path must not reach HTTP"))));

            assertEquals("gpu_device_lost", blocked.reasonCode());
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static DynamicChatModelFactory configuredNativeFactory(String baseUrl) {
        MockEnvironment environment = new MockEnvironment().withProperty("llm.api-key", "ollama");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(environment, new KeyResolver(environment));
        ReflectionTestUtils.setField(factory, "defaultModelName", "qwen3:8b");
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
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);
        ReflectionTestUtils.setField(factory, "ollamaNativeNumGpu", "");
        return factory;
    }

    private static DynamicChatModelFactory configuredNativeFactory(
            String baseUrl,
            ModelRuntimeHealthTracker tracker,
            LlmGatewayProperties gatewayProperties) {
        MockEnvironment environment = new MockEnvironment().withProperty("llm.api-key", "ollama");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(
                environment, new KeyResolver(environment), tracker, gatewayProperties);
        ReflectionTestUtils.setField(factory, "defaultModelName", "qwen3:8b");
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
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);
        ReflectionTestUtils.setField(factory, "ollamaNativeNumGpu", "");
        return factory;
    }
}
