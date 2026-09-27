package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OpenAiEndpointCompatibility;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LocalGatewayHealthRoutingTest {
    @AfterEach void clear() { TraceStore.clear(); }

    @Test
    void healthDownThresholdBypassesFourthPhysicalCallAndCancellationDoesNotCount() {
        var tracker = new ModelRuntimeHealthTracker();
        var props = policy();
        var probe = probe(props, tracker, Map.of());
        String endpoint = "http://127.0.0.1:11435/v1";
        AtomicInteger calls = new AtomicInteger();
        var guarded = probe.guardLocalModel(new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                calls.incrementAndGet();
                throw new LlmGatewayException("synthetic connection failure", LlmFailureClass.HEALTH_DOWN);
            }
        }, endpoint, "qwen3:8b");
        for (int i = 0; i < 3; i++) assertThrows(RuntimeException.class, () -> guarded.chat(input()));
        TraceStore.clear();assertThrows(RuntimeException.class, () -> guarded.chat(input()));
        assertEquals("open",TraceStore.get("llm.localEndpoint.state"));
        assertEquals(3, calls.get());
        assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN,
                tracker.endpointSnapshot("local", endpoint, System.currentTimeMillis()).orElseThrow().state());
    }

    @Test
    void cpuAndWrongModelCannotRecoverButFreshTargetVramCan() throws Exception {
        for (String payload : List.of("{\"models\":[{\"name\":\"qwen3:8b\",\"size_vram\":0}]}",
                "{\"models\":[{\"name\":\"other:8b\",\"size_vram\":1000}]}",
                "{\"models\":[{\"name\":\"qwen3:8b\",\"size_vram\":1000}]}")) {
            AtomicInteger psCalls = new AtomicInteger();
            var server = psServer(payload, psCalls);
            try {
                String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
                var tracker = new ModelRuntimeHealthTracker();
                var props = policy();
                var probe = probe(props, tracker, Map.of("status", "ok", "available", true, "observationAgeMs", 0));
                tracker.recordEndpointDeviceLoss("local", endpoint,
                        props.getLocalDeviceFailover().toEndpointQuarantinePolicy(), System.currentTimeMillis() - 1000);
                var guarded = probe.guardLocalModel(success(), endpoint, "qwen3:8b");
                assertEquals("verified answer [source-7]", guarded.chat(input()).aiMessage().text());
                boolean expectedGpu = payload.contains("qwen3:8b") && !payload.contains(":0");
                assertEquals(expectedGpu ? ModelRuntimeHealthTracker.EndpointState.CLOSED : ModelRuntimeHealthTracker.EndpointState.OPEN,
                        tracker.endpointSnapshot("local", endpoint, System.currentTimeMillis()).orElseThrow().state());
                assertEquals(1, psCalls.get());
                assertEquals(expectedGpu, TraceStore.get("llm.localEndpoint.gpuRecoveryVerified"));
                assertFalse(TraceStore.context().toString().contains("verified answer"));
            } finally { server.stop(0); }
        }
    }

    @Test
    void unknownHardwareDoesNotClaimRecoveryOrProbeAnotherService() throws Exception {
        AtomicInteger psCalls = new AtomicInteger();
        var server = psServer("{\"models\":[]}", psCalls);
        try {
            var tracker = new ModelRuntimeHealthTracker();
            var props = policy();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            tracker.recordEndpointDeviceLoss("local", endpoint,
                    props.getLocalDeviceFailover().toEndpointQuarantinePolicy(), System.currentTimeMillis() - 1000);
            probe(props, tracker, Map.of("status", "disabled_by_config")).guardLocalModel(success(), endpoint, "qwen3:8b").chat(input());
            assertEquals(0, psCalls.get());
            assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN,
                    tracker.endpointSnapshot("local", endpoint, System.currentTimeMillis()).orElseThrow().state());
        } finally { server.stop(0); }
    }

    @Test
    void cancelledHalfOpenCallReleasesPermitAndKeepsFailureCount() {
        var tracker = new ModelRuntimeHealthTracker();
        var props = policy();
        String endpoint = "http://127.0.0.1:11435/v1";
        var configured = props.getLocalDeviceFailover().toEndpointQuarantinePolicy();
        tracker.recordEndpointDeviceLoss("local", endpoint, configured, System.currentTimeMillis() - 1000);
        var guarded = probe(props, tracker, Map.of()).guardLocalModel(new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) { throw new CancellationException(); }
        }, endpoint, "qwen3:8b");
        assertThrows(CancellationException.class, () -> guarded.chat(input()));
        assertEquals(1, tracker.endpointSnapshot("local", endpoint, System.currentTimeMillis()).orElseThrow().failureCount());
        assertTrue(tracker.acquireEndpointAccess("local", endpoint, configured, System.currentTimeMillis()).halfOpenPermit());
    }

    @Test
    void historicalFailureCannotBlockExpiredCircuitRecoveryForever() {
        var tracker = new ModelRuntimeHealthTracker();
        var props = policy();
        String endpoint = "http://127.0.0.1:11435/v1";
        tracker.recordFailure("local", "qwen3:8b", OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "health_down");
        tracker.recordEndpointDeviceLoss("local", endpoint,
                props.getLocalDeviceFailover().toEndpointQuarantinePolicy(), System.currentTimeMillis() - 1000);
        var cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(true); cfg.setProvider("local"); cfg.setName("qwen3:8b"); cfg.setBaseUrl(endpoint);
        assertTrue(probe(props, tracker, Map.of()).evaluate("local", cfg, "chat").eligible());
    }

    @Test
    void anotherGpuCannotCertifyRecoveryOfAnExplicit3090Route() throws Exception {
        var calls = new AtomicInteger();
        var server = psServer("{\"models\":[{\"name\":\"qwen3:8b\",\"size_vram\":1000}]}", calls);
        try {
            var tracker = new ModelRuntimeHealthTracker();
            var props = policy();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            tracker.recordEndpointDeviceLoss("local", endpoint,
                    props.getLocalDeviceFailover().toEndpointQuarantinePolicy(), System.currentTimeMillis() - 1000);
            probe(props, tracker, Map.of("status", "ok", "available", true, "observationAgeMs", 0,
                    "hasRtx3060", true, "hasRtx3090", false))
                    .guardLocalModel(success(), endpoint, "qwen3:8b", "rtx3090").chat(input());
            assertEquals(0, calls.get());
            assertEquals(false, TraceStore.get("llm.localEndpoint.gpuRecoveryVerified"));
            assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN,
                    tracker.endpointSnapshot("local", endpoint, System.currentTimeMillis()).orElseThrow().state());
        } finally { server.stop(0); }
    }

    @Test
    void eligibilityPublishesOpenStateBeforeAnyLocalDispatch() {
        var tracker = new ModelRuntimeHealthTracker();
        var props = policy();
        String endpoint = "http://127.0.0.1:11435/v1";
        tracker.recordEndpointDeviceLoss("local", endpoint,
                props.getLocalDeviceFailover().toEndpointQuarantinePolicy(), System.currentTimeMillis());
        TraceStore.clear();
        var cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(true); cfg.setProvider("local"); cfg.setName("qwen3:8b"); cfg.setBaseUrl(endpoint);
        probe(props, tracker, Map.of()).evaluate("local", cfg, "chat");
        assertEquals("open", TraceStore.get("llm.localEndpoint.state"));
        assertNotNull(TraceStore.get("llm.localEndpoint.reason"));
    }

    private static LlmGatewayProperties policy() {
        var props = new LlmGatewayProperties();
        props.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        props.getLocalDeviceFailover().setEnabled(true);
        props.getLocalDeviceFailover().setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        props.getLocalDeviceFailover().setHardCooldownMs(100);
        props.getLocalDeviceFailover().setRecoveryStableMs(0);
        props.getLocalDeviceFailover().setRecoverySuccesses(1);
        props.getLocalDeviceFailover().setHealthSampleIntervalMs(0);
        return props;
    }
    private static HybridLlmGatewayProbeService probe(LlmGatewayProperties props, ModelRuntimeHealthTracker tracker,
            Map<String, Object> hardware) {
        return new HybridLlmGatewayProbeService(props, tracker, null, new LlmRouteScorer(), new MockEnvironment()) {
            @Override protected Map<String, Object> gpuHardwareSnapshot() { return hardware; }
        };
    }
    private static List<ChatMessage> input() { return List.of(UserMessage.from("synthetic source-7")); }
    private static ChatModel success() { return new ChatModel() {
        @Override public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder().aiMessage(AiMessage.from("verified answer [source-7]")).build();
        }
    }; }
    private static HttpServer psServer(String body, AtomicInteger calls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ps", exchange -> {
            calls.incrementAndGet(); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start(); return server;
    }
}
