package com.example.lms.llm;

import org.junit.jupiter.api.Test;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import static org.junit.jupiter.api.Assertions.*;

class LocalEndpointRecoveryFenceTest {
    private static final String ENDPOINT = "http://127.0.0.1:11435/v1";
    private final ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
    private final ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
            new ModelRuntimeHealthTracker.EndpointQuarantinePolicy(true, true, 100, 60_000, 2, 2, 16);

    @Test
    void anAbandonedProbeExpiresInsteadOfKeepingTheEndpointBusyForever() {
        tracker.recordEndpointDeviceLoss("local", ENDPOINT, policy, 1_000);
        var abandoned = tracker.acquireEndpointAccess("local", ENDPOINT, policy, 1_100);
        assertTrue(abandoned.halfOpenPermit());
        var expired = tracker.acquireEndpointAccess("local", ENDPOINT, policy, 61_101);
        assertFalse(expired.allowed());
        assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN, expired.state());
        tracker.completeEndpointAccess(abandoned, true, policy, 61_102);
        assertEquals(0, tracker.endpointSnapshot("local", ENDPOINT, 61_102).orElseThrow()
                .consecutiveGpuPrimarySuccesses());
        assertTrue(tracker.acquireEndpointAccess("local", ENDPOINT, policy, 61_201).halfOpenPermit());
    }

    @Test
    void duplicateCompletionOfPreviousProbeCannotCompleteTheNextProbe() {
        tracker.recordEndpointDeviceLoss("local", ENDPOINT, policy, 1_000);
        var first = tracker.acquireEndpointAccess("local", ENDPOINT, policy, 1_100);
        tracker.completeEndpointAccess(first, true, policy, 1_101);
        var second = tracker.acquireEndpointAccess("local", ENDPOINT, policy, 1_102);
        tracker.completeEndpointAccess(first, true, policy, 1_103);
        var pending = tracker.endpointSnapshot("local", ENDPOINT, 1_103).orElseThrow();
        assertEquals(ModelRuntimeHealthTracker.EndpointState.HALF_OPEN, pending.state());
        assertTrue(pending.halfOpenProbeInFlight());
        assertEquals(1, pending.consecutiveGpuPrimarySuccesses());
        tracker.completeEndpointAccess(second, true, policy, 1_104);
        assertEquals(ModelRuntimeHealthTracker.EndpointState.CLOSED,
                tracker.endpointSnapshot("local", ENDPOINT, 1_104).orElseThrow().state());
    }

    @Test
    void cancellationReleasesOnlyItsOwnPermitWithoutCountingFailure() {
        tracker.recordEndpointDeviceLoss("local", ENDPOINT, policy, 1_000);
        var cancelled = tracker.acquireEndpointAccess("local", ENDPOINT, policy, 1_100);
        tracker.releaseEndpointAccess(cancelled);
        var next = tracker.acquireEndpointAccess("local", ENDPOINT, policy, 1_101);
        assertTrue(next.halfOpenPermit());
        tracker.releaseEndpointAccess(cancelled);
        tracker.completeEndpointAccess(cancelled, true, policy, 1_102);
        var state = tracker.endpointSnapshot("local", ENDPOINT, 1_102).orElseThrow();
        assertTrue(state.halfOpenProbeInFlight());
        assertEquals(1, state.failureCount());
        assertEquals(0, state.consecutiveGpuPrimarySuccesses());
    }

    @Test
    void successfulContentAloneCannotSkipConfiguredRecoveryDwell() {
        var settings = new LlmGatewayProperties.LocalDeviceFailover();
        settings.setEnabled(true);
        settings.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        settings.setHardCooldownMs(100);
        var configured = settings.toEndpointQuarantinePolicy();
        tracker.recordEndpointDeviceLoss("local", ENDPOINT, configured, 1_000);
        var first = tracker.acquireEndpointAccess("local", ENDPOINT, configured, 1_100);
        tracker.completeEndpointAccess(first, true, configured, 1_101);
        assertFalse(tracker.acquireEndpointAccess("local", ENDPOINT, configured, 1_102).allowed());
        var second = tracker.acquireEndpointAccess("local", ENDPOINT, configured, 11_101);
        tracker.completeEndpointAccess(second, true, configured, 11_102);
        assertEquals(ModelRuntimeHealthTracker.EndpointState.HALF_OPEN,
                tracker.endpointSnapshot("local", ENDPOINT, 11_102).orElseThrow().state());
        var stable = tracker.acquireEndpointAccess("local", ENDPOINT, configured, 31_101);
        tracker.completeEndpointAccess(stable, true, configured, 31_102);
        assertEquals(ModelRuntimeHealthTracker.EndpointState.CLOSED,
                tracker.endpointSnapshot("local", ENDPOINT, 31_102).orElseThrow().state());
    }

    @Test
    void threeTransientFailuresTripOnlyTheirEndpointAndModelCounter() {
        tracker.recordEndpointTransientFailure("local", ENDPOINT, "model-a", LlmFailureClass.TIMEOUT_SOFT, policy, 1_000);
        tracker.recordEndpointTransientFailure("local", ENDPOINT, "model-a", LlmFailureClass.HEALTH_DOWN, policy, 1_001);
        tracker.recordEndpointModelSuccess("local", ENDPOINT, "model-b");
        tracker.recordEndpointTransientFailure("local", ENDPOINT, "model-b", LlmFailureClass.VRAM_OOM, policy, 1_002);
        assertTrue(tracker.acquireEndpointAccess("local", ENDPOINT, policy, 1_003).allowed());
        tracker.recordEndpointTransientFailure("local", ENDPOINT, "model-a", LlmFailureClass.PROVIDER_ERROR, policy, 1_004);
        assertFalse(tracker.acquireEndpointAccess("local", ENDPOINT, policy, 1_005).allowed());
        assertTrue(tracker.acquireEndpointAccess("local", "http://127.0.0.1:11434", policy, 1_005).allowed());
        assertEquals(60_000, tracker.endpointSnapshot("local", ENDPOINT, 1_004).orElseThrow().retryAfterMs());
    }

    @Test
    void sameModelSuccessResetsConsecutiveFailuresAndExpiredWindowsDoNotAccumulate() {
        tracker.recordEndpointTransientFailure("local", ENDPOINT, "model", LlmFailureClass.TIMEOUT_SOFT, policy, 1_000);
        tracker.recordEndpointTransientFailure("local", ENDPOINT, "model", LlmFailureClass.TIMEOUT_SOFT, policy, 1_001);
        tracker.recordEndpointModelSuccess("local", ENDPOINT, "model");
        tracker.recordEndpointTransientFailure("local", ENDPOINT, "model", LlmFailureClass.TIMEOUT_SOFT, policy, 1_002);
        tracker.recordEndpointTransientFailure("local", ENDPOINT, "model", LlmFailureClass.TIMEOUT_SOFT, policy, 62_000);
        tracker.recordEndpointTransientFailure("local", ENDPOINT, "model", LlmFailureClass.TIMEOUT_SOFT, policy, 62_001);
        assertTrue(tracker.acquireEndpointAccess("local", ENDPOINT, policy, 62_002).allowed());
    }

    @Test
    void cancellationsCredentialsAndBadInputNeverOpenTheGpuCircuit() {
        for (var failure : new LlmFailureClass[]{LlmFailureClass.CANCELLED_NEUTRAL, LlmFailureClass.AUTH_MISSING,
                LlmFailureClass.BAD_REQUEST, LlmFailureClass.CONTEXT_TOO_SMALL}) {
            for (int i = 0; i < 4; i++)
                tracker.recordEndpointTransientFailure("local", ENDPOINT, "model", failure, policy, 1_000 + i);
        }
        assertTrue(tracker.endpointSnapshot("local", ENDPOINT, 1_005).isEmpty());
        assertTrue(tracker.acquireEndpointAccess("local", ENDPOINT, policy, 1_005).allowed());
    }

    @Test
    void nativeCudaFailuresOpenCircuitAndFourthRequestNeverReachesTransport() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] body = "{\"error\":\"CUDA runtime error\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var model = new OllamaNativeChatModel(endpoint, "qwen3:8b", java.time.Duration.ofSeconds(2),
                    32, 0.0, null, tracker, false, policy);
            for (int i = 0; i < 4; i++) assertThrows(RuntimeException.class,
                    () -> model.chat(java.util.List.of(dev.langchain4j.data.message.UserMessage.from("synthetic"))));
            assertEquals(3, calls.get());
            assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN,
                    tracker.endpointSnapshot("local", endpoint, System.currentTimeMillis()).orElseThrow().state());
        } finally { server.stop(0); }
    }
}
