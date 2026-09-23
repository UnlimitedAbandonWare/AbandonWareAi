package com.example.lms.llm;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.llm.gateway.LlmRouteScorer;
import com.example.lms.llm.spec.ModelSpecRegistry;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEndpointDeviceLossContractTest {

    @Test
    void disabledPolicyRetainsNoEndpointStateAndNeverBlocks() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String endpoint = "http://127.0.0.1:11435/v1";
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy disabled =
                ModelRuntimeHealthTracker.EndpointQuarantinePolicy.disabled();

        tracker.recordEndpointDeviceLoss("local", endpoint, disabled, 1_000L);
        ModelRuntimeHealthTracker.EndpointAccess access =
                tracker.acquireEndpointAccess("local", endpoint, disabled, 1_001L);

        assertTrue(tracker.endpointSnapshot("local", endpoint, 1_001L).isEmpty());
        assertTrue(access.allowed());
        assertFalse(access.wouldBlock());
        assertFalse(access.halfOpenPermit());
    }

    @Test
    void hardDeviceLossOpensOnlyTheFailedNormalizedEndpointWithoutRetainingRawUrl() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String failedEndpoint = "http://operator:private@127.0.0.1:11435/v1?token=private";
        String sameEndpoint = "http://127.0.0.1:11435/api/chat";
        String healthyEndpoint = "http://127.0.0.1:11434/v1";
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy = policy(true);

        tracker.recordEndpointDeviceLoss("local", failedEndpoint, policy, 1_000L);

        Optional<ModelRuntimeHealthTracker.EndpointSnapshot> failed =
                tracker.endpointSnapshot("local", sameEndpoint, 1_001L);
        Optional<ModelRuntimeHealthTracker.EndpointSnapshot> healthy =
                tracker.endpointSnapshot("local", healthyEndpoint, 1_001L);

        assertTrue(failed.isPresent());
        assertTrue(healthy.isEmpty());
        ModelRuntimeHealthTracker.EndpointSnapshot snapshot = failed.orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN, snapshot.state());
        assertEquals("gpu_device_lost", snapshot.lastReason());
        assertNotEquals("unknown", snapshot.endpointHash());
        String publicText = snapshot.toString();
        assertFalse(publicText.contains("operator"), publicText);
        assertFalse(publicText.contains("private"), publicText);
        assertFalse(publicText.contains("127.0.0.1"), publicText);
    }

    @Test
    void cooldownAllowsOneHalfOpenProbeAcrossTwentyConcurrentRequests() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
                new ModelRuntimeHealthTracker.EndpointQuarantinePolicy(
                        true, true, 100L, 60_000L, 2, 2, 16);
        String endpoint = "http://127.0.0.1:11435/v1";
        tracker.recordEndpointDeviceLoss("local", endpoint, policy, 1_000L);

        ModelRuntimeHealthTracker.EndpointAccess beforeCooldown =
                tracker.acquireEndpointAccess("local", endpoint, policy, 1_099L);
        assertFalse(beforeCooldown.allowed());
        assertTrue(beforeCooldown.wouldBlock());
        assertFalse(beforeCooldown.halfOpenPermit());

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ModelRuntimeHealthTracker.EndpointAccess>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return tracker.acquireEndpointAccess("local", endpoint, policy, 1_100L);
                }));
            }
            ready.await();
            start.countDown();

            int permitCount = 0;
            int allowedCount = 0;
            for (Future<ModelRuntimeHealthTracker.EndpointAccess> future : futures) {
                ModelRuntimeHealthTracker.EndpointAccess access = future.get();
                permitCount += access.halfOpenPermit() ? 1 : 0;
                allowedCount += access.allowed() ? 1 : 0;
            }
            assertEquals(1, permitCount);
            assertEquals(1, allowedCount);
        } finally {
            executor.shutdownNow();
        }

        ModelRuntimeHealthTracker.EndpointSnapshot snapshot =
                tracker.endpointSnapshot("local", endpoint, 1_100L).orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.HALF_OPEN, snapshot.state());
        assertTrue(snapshot.halfOpenProbeInFlight());
    }

    @Test
    void cpuFallbackSuccessWithoutHalfOpenPermitDoesNotClearGpuQuarantine() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy observePolicy = policy(false);
        String endpoint = "http://127.0.0.1:11435/v1";
        tracker.recordEndpointDeviceLoss("local", endpoint, observePolicy, 1_000L);
        ModelRuntimeHealthTracker.EndpointAccess cpuFallbackAccess =
                tracker.acquireEndpointAccess("local", endpoint, observePolicy, 1_001L);
        assertTrue(cpuFallbackAccess.allowed());
        assertFalse(cpuFallbackAccess.halfOpenPermit());

        tracker.completeEndpointAccess(cpuFallbackAccess, true, observePolicy, 1_002L);

        ModelRuntimeHealthTracker.EndpointSnapshot snapshot =
                tracker.endpointSnapshot("local", endpoint, 1_002L).orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN, snapshot.state());
        assertEquals(0, snapshot.consecutiveGpuPrimarySuccesses());
    }

    @Test
    void apiVersionAndTagsSuccessDoNotCloseGenerationQuarantine() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy = policy(true);
        String endpoint = "http://127.0.0.1:11435/v1";
        tracker.recordEndpointDeviceLoss("local", endpoint, policy, 1_000L);

        tracker.recordSuccess(
                "local",
                "qwen3:8b",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        ModelRuntimeHealthTracker.EndpointSnapshot snapshot =
                tracker.endpointSnapshot("local", endpoint, 1_001L).orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN, snapshot.state());
        assertEquals(0, snapshot.consecutiveGpuPrimarySuccesses());
    }

    @Test
    void twoConsecutiveGpuPrimarySuccessesCloseHalfOpenState() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
                new ModelRuntimeHealthTracker.EndpointQuarantinePolicy(
                        true, true, 100L, 60_000L, 2, 2, 16);
        String endpoint = "http://127.0.0.1:11435/v1";
        tracker.recordEndpointDeviceLoss("local", endpoint, policy, 1_000L);

        ModelRuntimeHealthTracker.EndpointAccess first =
                tracker.acquireEndpointAccess("local", endpoint, policy, 1_100L);
        assertTrue(first.halfOpenPermit());
        tracker.completeEndpointAccess(first, true, policy, 1_101L);

        ModelRuntimeHealthTracker.EndpointSnapshot afterFirst =
                tracker.endpointSnapshot("local", endpoint, 1_101L).orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.HALF_OPEN, afterFirst.state());
        assertEquals(1, afterFirst.consecutiveGpuPrimarySuccesses());
        assertFalse(afterFirst.halfOpenProbeInFlight());

        ModelRuntimeHealthTracker.EndpointAccess second =
                tracker.acquireEndpointAccess("local", endpoint, policy, 1_102L);
        assertTrue(second.halfOpenPermit());
        tracker.completeEndpointAccess(second, true, policy, 1_103L);

        ModelRuntimeHealthTracker.EndpointSnapshot recovered =
                tracker.endpointSnapshot("local", endpoint, 1_103L).orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.CLOSED, recovered.state());
        assertEquals(2, recovered.consecutiveGpuPrimarySuccesses());
        assertFalse(recovered.halfOpenProbeInFlight());
        assertEquals("recovered", recovered.lastReason());
    }

    @Test
    void failedHalfOpenGpuPrimaryProbeReopensAndRenewsCooldown() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
                new ModelRuntimeHealthTracker.EndpointQuarantinePolicy(
                        true, true, 100L, 60_000L, 2, 2, 16);
        String endpoint = "http://127.0.0.1:11435/v1";
        tracker.recordEndpointDeviceLoss("local", endpoint, policy, 1_000L);
        ModelRuntimeHealthTracker.EndpointAccess probe =
                tracker.acquireEndpointAccess("local", endpoint, policy, 1_100L);
        assertTrue(probe.halfOpenPermit());

        tracker.completeEndpointAccess(probe, false, policy, 1_101L);

        ModelRuntimeHealthTracker.EndpointSnapshot reopened =
                tracker.endpointSnapshot("local", endpoint, 1_101L).orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN, reopened.state());
        assertEquals("half_open_probe_failed", reopened.lastReason());
        assertEquals(2L, reopened.failureCount());
        assertEquals(100L, reopened.retryAfterMs());
        assertFalse(reopened.halfOpenProbeInFlight());
        assertEquals(0, reopened.consecutiveGpuPrimarySuccesses());
    }

    @Test
    void runnerTerminationRequiresBoundedThresholdUnlessHardwareIsMissing() {
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
                new ModelRuntimeHealthTracker.EndpointQuarantinePolicy(
                        true, true, 1_000L, 60_000L, 2, 2, 16);
        String endpoint = "http://127.0.0.1:11435/v1";
        ModelRuntimeHealthTracker thresholdTracker = new ModelRuntimeHealthTracker();
        thresholdTracker.recordEndpointRunnerTermination(
                "local", endpoint, false, policy, 1_000L);
        ModelRuntimeHealthTracker.EndpointSnapshot first =
                thresholdTracker.endpointSnapshot("local", endpoint, 1_000L).orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.CLOSED, first.state());
        assertEquals(1, first.runnerFailureCount());

        thresholdTracker.recordEndpointRunnerTermination(
                "local", endpoint, false, policy, 1_050L);
        ModelRuntimeHealthTracker.EndpointSnapshot thresholdOpen =
                thresholdTracker.endpointSnapshot("local", endpoint, 1_050L).orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN, thresholdOpen.state());
        assertEquals("gpu_runner_terminated", thresholdOpen.lastReason());

        ModelRuntimeHealthTracker windowTracker = new ModelRuntimeHealthTracker();
        windowTracker.recordEndpointRunnerTermination(
                "local", endpoint, false, policy, 1_000L);
        windowTracker.recordEndpointRunnerTermination(
                "local", endpoint, false, policy, 61_001L);
        ModelRuntimeHealthTracker.EndpointSnapshot outsideWindow =
                windowTracker.endpointSnapshot("local", endpoint, 61_001L).orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.CLOSED, outsideWindow.state());
        assertEquals(1, outsideWindow.runnerFailureCount());

        ModelRuntimeHealthTracker hardwareTracker = new ModelRuntimeHealthTracker();
        hardwareTracker.recordEndpointRunnerTermination(
                "local", endpoint, true, policy, 1_000L);
        assertEquals(
                ModelRuntimeHealthTracker.EndpointState.OPEN,
                hardwareTracker.endpointSnapshot("local", endpoint, 1_000L).orElseThrow().state());
    }

    @Test
    void endpointStateMapEvictsTheOldestEntryAtItsBound() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy bounded =
                new ModelRuntimeHealthTracker.EndpointQuarantinePolicy(
                        true, true, 600_000L, 60_000L, 2, 2, 2);
        String first = "http://127.0.0.1:11431/v1";
        String second = "http://127.0.0.1:11432/v1";
        String third = "http://127.0.0.1:11433/v1";

        tracker.recordEndpointDeviceLoss("local", first, bounded, 1_000L);
        tracker.recordEndpointDeviceLoss("local", second, bounded, 1_001L);
        tracker.recordEndpointDeviceLoss("local", third, bounded, 1_002L);

        assertTrue(tracker.endpointSnapshot("local", first, 1_003L).isEmpty());
        assertTrue(tracker.endpointSnapshot("local", second, 1_003L).isPresent());
        assertTrue(tracker.endpointSnapshot("local", third, 1_003L).isPresent());
    }

    @Test
    void deterministicTwoEndpointLoopbackKeepsAOpenAndCallsBOnceOnNextAutoRequest() throws Exception {
        AtomicInteger endpointACalls = new AtomicInteger();
        AtomicInteger endpointBCalls = new AtomicInteger();
        HttpServer endpointA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer endpointB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpointA.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = endpointACalls.incrementAndGet();
            byte[] body = (call == 1
                    ? "{\"error\":\"invalid main_gpu selection (available devices: 0)\"}"
                    : "{\"message\":{\"content\":\"CPU_OK\"},\"done_reason\":\"stop\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(call == 1 ? 500 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        endpointB.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            endpointBCalls.incrementAndGet();
            byte[] body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"B_OK\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        endpointA.start();
        endpointB.start();
        try {
            String baseA = "http://127.0.0.1:" + endpointA.getAddress().getPort() + "/v1";
            String baseB = "http://127.0.0.1:" + endpointB.getAddress().getPort() + "/v1";
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            LlmGatewayProperties gatewayProperties = new LlmGatewayProperties();
            gatewayProperties.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
            gatewayProperties.getSpecRegistry().setEnabled(false);
            gatewayProperties.getLocalDeviceFailover().setEnabled(true);
            gatewayProperties.getLocalDeviceFailover().setEnforcement(
                    LlmGatewayProperties.Enforcement.ENFORCE);
            ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
                    gatewayProperties.getLocalDeviceFailover().toEndpointQuarantinePolicy();
            ChatModel firstModel = new OllamaNativeChatModel(
                    baseA, "qwen3:8b", Duration.ofSeconds(2), 32, 0.1d, null,
                    null, tracker, true, policy);

            String firstPrompt = "loopback private A prompt";
            assertEquals("CPU_OK", firstModel.chat(List.of(UserMessage.from(firstPrompt))).aiMessage().text());

            LlmRouterProperties routerProperties = new LlmRouterProperties();
            Map<String, LlmRouterProperties.ModelConfig> routes = new LinkedHashMap<>();
            LlmRouterProperties.ModelConfig routeA = localRoute("qwen3:8b", baseA, 10.0d);
            LlmRouterProperties.ModelConfig sharedA = localRoute("gemma4:26b", baseA + "/other", 9.0d);
            LlmRouterProperties.ModelConfig routeB = localRoute("gemma3:4b", baseB, 1.0d);
            routes.put("a-primary", routeA);
            routes.put("a-shared", sharedA);
            routes.put("b-healthy", routeB);
            routerProperties.setModels(routes);
            HybridLlmGatewayProbeService gateway = new HybridLlmGatewayProbeService(
                    gatewayProperties,
                    tracker,
                    new ModelSpecRegistry(new ObjectMapper(), gatewayProperties),
                    new LlmRouteScorer());
            LlmRouterBandit.Selected selected = new LlmRouterBandit(routerProperties).pick(
                    "llmrouter.auto",
                    (key, cfg) -> gateway.evaluate(key, cfg, "chat").eligible());
            assertEquals("b-healthy", selected.key());
            ChatModel secondModel = OpenAiChatModel.builder()
                    .baseUrl(selected.cfg().getBaseUrl())
                    .apiKey("ollama")
                    .modelName(selected.cfg().getName())
                    .maxRetries(0)
                    .timeout(Duration.ofSeconds(2))
                    .build();
            String secondPrompt = "loopback private B prompt";
            assertEquals("B_OK", secondModel.chat(List.of(UserMessage.from(secondPrompt))).aiMessage().text());

            assertEquals(2, endpointACalls.get());
            assertEquals(1, endpointBCalls.get());
            ModelRuntimeHealthTracker.EndpointSnapshot failed =
                    tracker.endpointSnapshot("local", baseA, System.currentTimeMillis()).orElseThrow();
            assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN, failed.state());
            assertEquals(0, failed.consecutiveGpuPrimarySuccesses());
            assertTrue(tracker.endpointSnapshot("local", baseB, System.currentTimeMillis()).isEmpty(),
                    "an unquarantined endpoint is equivalent to CLOSED and must not inherit A state");
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(firstPrompt), trace);
            assertFalse(trace.contains(secondPrompt), trace);
            assertFalse(trace.contains("available devices"), trace);
        } finally {
            endpointA.stop(0);
            endpointB.stop(0);
            TraceStore.clear();
        }
    }

    private static LlmRouterProperties.ModelConfig localRoute(String name, String baseUrl, double weight) {
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(true);
        cfg.setProvider("local");
        cfg.setStage("chat");
        cfg.setName(name);
        cfg.setBaseUrl(baseUrl);
        cfg.setWeight(weight);
        return cfg;
    }

    private static ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy(boolean enforce) {
        return new ModelRuntimeHealthTracker.EndpointQuarantinePolicy(
                true, enforce, 600_000L, 60_000L, 2, 2, 16);
    }
}
