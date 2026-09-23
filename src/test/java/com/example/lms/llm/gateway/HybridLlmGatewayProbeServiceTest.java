package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.llm.spec.ModelSpecRegistry;
import com.example.lms.llm.spec.ModelSpecSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridLlmGatewayProbeServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void blocksWhenObservedSpecContextIsTooSmall() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setPath("build/tmp/test-model-spec-probe.json");
        ModelSpecRegistry registry = new ModelSpecRegistry(new ObjectMapper(), props);
        registry.publish(ModelSpecSnapshot.of("local", "small", "localhost", 1024, null, List.of("chat"), Map.of()));
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("small");
        cfg.setBaseUrl("http://localhost:11434/v1");
        cfg.setMinContextTokens(4096);

        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props, new ModelRuntimeHealthTracker(), registry, new LlmRouteScorer());

        RoutingEligibility eligibility = service.evaluate("small", cfg, "chat");

        assertFalse(eligibility.eligible());
        assertTrue(eligibility.failureClasses().contains(LlmFailureClass.CONTEXT_TOO_SMALL));
    }

    @Test
    void managedFileSearchIsNotEligibleOnLocalRoutes() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setEnabled(false);
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("gemma3:4b");
        cfg.setBaseUrl("http://localhost:11434/v1");
        cfg.setManagedFileSearch(true);

        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props, new ModelRuntimeHealthTracker(), new ModelSpecRegistry(new ObjectMapper(), props), new LlmRouteScorer());

        RoutingEligibility eligibility = service.evaluate("gemma", cfg, "chat");

        assertFalse(eligibility.eligible());
        assertTrue(eligibility.failureClasses().contains(LlmFailureClass.LOCAL_UNSUPPORTED_MANAGED_RAG));
    }

    @Test
    void exposesRuntimeHealthPressureMetadataWithoutBlockingRecoveredRoute() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setEnabled(false);
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("qwen3:8b");
        cfg.setBaseUrl("http://localhost:11434/v1");
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        tracker.recordFailure("local", "qwen3:8b",
                com.example.lms.llm.OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "blank_response");
        tracker.recordFailure("local", "qwen3:8b",
                com.example.lms.llm.OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "upstream_5xx");
        tracker.recordSuccess("local", "qwen3:8b",
                com.example.lms.llm.OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);

        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props, tracker, new ModelSpecRegistry(new ObjectMapper(), props), new LlmRouteScorer());

        RoutingEligibility eligibility = service.evaluate("qwen-fast", cfg, "chat");

        assertTrue(eligibility.eligible());
        assertEquals(2L, eligibility.safeMeta().get("healthFailureCount"));
        assertEquals(3L, eligibility.safeMeta().get("healthSampleCount"));
        assertEquals("llm_route_degrade", eligibility.safeMeta().get("healthRoutingHint"));
        double pressure = ((Number) eligibility.safeMeta().get("healthFailurePressure")).doubleValue();
        assertTrue(pressure > 0.0d && pressure < 1.0d);
    }

    @Test
    void quarantinedEndpointBlocksEverySharingRouteButNotDifferentEndpoint() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setEnabled(false);
        props.getLocalDeviceFailover().setEnabled(true);
        props.getLocalDeviceFailover().setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        long now = System.currentTimeMillis();
        tracker.recordEndpointDeviceLoss(
                "local",
                "http://localhost:11435/v1",
                props.getLocalDeviceFailover().toEndpointQuarantinePolicy(),
                now);
        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props,
                tracker,
                new ModelSpecRegistry(new ObjectMapper(), props),
                new LlmRouteScorer());

        LlmRouterProperties.ModelConfig primary = localRoute(
                "qwen3:8b", "http://localhost:11435/v1");
        LlmRouterProperties.ModelConfig shared = localRoute(
                "gemma4:26b", "http://localhost:11435/api/chat?probe=false");
        LlmRouterProperties.ModelConfig healthy = localRoute(
                "qwen3:8b", "http://localhost:11434/v1");

        RoutingEligibility primaryResult = service.evaluate("fast", primary, "chat");
        RoutingEligibility sharedResult = service.evaluate("high", shared, "chat");
        RoutingEligibility healthyResult = service.evaluate("healthy", healthy, "chat");

        assertFalse(primaryResult.eligible());
        assertFalse(sharedResult.eligible());
        assertTrue(primaryResult.failureClasses().contains(LlmFailureClass.GPU_DEVICE_LOST));
        assertTrue(sharedResult.failureClasses().contains(LlmFailureClass.GPU_DEVICE_LOST));
        assertTrue(healthyResult.eligible());
        assertEquals("open", primaryResult.safeMeta().get("endpointState"));
        assertEquals(true, primaryResult.safeMeta().get("endpointWouldBlock"));
        assertTrue(String.valueOf(primaryResult.safeMeta().get("endpointHash")).startsWith("hash:"));
        assertFalse(primaryResult.asBreadcrumb().toString().contains("11435/v1"));
    }

    @Test
    void observeModePublishesWouldBlockWithoutChangingEligibilityOrState() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setEnabled(false);
        props.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        props.getLocalDeviceFailover().setEnabled(true);
        props.getLocalDeviceFailover().setEnforcement(LlmGatewayProperties.Enforcement.OBSERVE);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String endpoint = "http://localhost:11435/v1";
        tracker.recordEndpointDeviceLoss(
                "local",
                endpoint,
                props.getLocalDeviceFailover().toEndpointQuarantinePolicy(),
                System.currentTimeMillis());
        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props,
                tracker,
                new ModelSpecRegistry(new ObjectMapper(), props),
                new LlmRouteScorer());

        RoutingEligibility result = service.evaluate("fast", localRoute("qwen3:8b", endpoint), "chat");

        assertTrue(result.eligible());
        assertFalse(result.failureClasses().contains(LlmFailureClass.GPU_DEVICE_LOST));
        assertEquals(true, result.safeMeta().get("endpointWouldBlock"));
        assertEquals("open_observed", result.safeMeta().get("endpointSelectionDecision"));
        assertEquals(
                ModelRuntimeHealthTracker.EndpointState.OPEN,
                tracker.endpointSnapshot("local", endpoint, System.currentTimeMillis()).orElseThrow().state());
    }

    @Test
    void invalidEndpointHostLeavesRedactedTraceBreadcrumb() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setEnabled(false);
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("gemma3:4b");
        cfg.setBaseUrl("http://bad host/private-owner-token");

        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props, new ModelRuntimeHealthTracker(), new ModelSpecRegistry(new ObjectMapper(), props), new LlmRouteScorer());

        RoutingEligibility eligibility = service.evaluate("gemma", cfg, "chat");

        assertTrue(eligibility.eligible());
        assertTrue(eligibility.safeMeta().containsKey("endpointHost"));
        assertEquals(true, TraceStore.get("llm.gateway.probe.suppressed.endpointHost"));
        assertEquals("invalid_url",
                TraceStore.get("llm.gateway.probe.suppressed.endpointHost.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("private-owner-token"));
    }

    private static LlmRouterProperties.ModelConfig localRoute(String name, String baseUrl) {
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setProvider("local");
        cfg.setName(name);
        cfg.setBaseUrl(baseUrl);
        return cfg;
    }

    @Test
    void missingCloudCredentialBlocksExternalRouteBeforeAnyProviderCall() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setEnabled(false);
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setProvider("openai");
        cfg.setName("gpt-5.4-mini");
        cfg.setBaseUrl("https://api.openai.com/v1");
        cfg.setFallbackOnly(true);
        cfg.setWeight(0.0d);
        cfg.setCredentialEnv("OPENAI_API_KEY");

        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props,
                new ModelRuntimeHealthTracker(),
                new ModelSpecRegistry(new ObjectMapper(), props),
                new LlmRouteScorer(),
                new MockEnvironment().withProperty("OPENAI_API_KEY", "dummy"));

        RoutingEligibility eligibility = service.evaluate("openai-mini", cfg, "chat");

        assertFalse(eligibility.eligible());
        assertTrue(eligibility.failureClasses().contains(LlmFailureClass.AUTH_MISSING));
        assertEquals("missing OPENAI_API_KEY", eligibility.safeMeta().get("disabledReason"));
        assertEquals("OPENAI_API_KEY", eligibility.safeMeta().get("credentialEnv"));
        assertFalse(eligibility.asBreadcrumb().toString().contains("dummy"));
    }

    @Test
    void secretShapedCredentialEnvIsNotEchoedInRouteDiagnostics() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setEnabled(false);
        String rawToken = "sk-" + "routecredentialtoken1234567890";
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setProvider("openai");
        cfg.setName("gpt-5.4-mini");
        cfg.setBaseUrl("https://api.openai.com/v1");
        cfg.setCredentialEnv(rawToken);

        HybridLlmGatewayProbeService service = new HybridLlmGatewayProbeService(
                props,
                new ModelRuntimeHealthTracker(),
                new ModelSpecRegistry(new ObjectMapper(), props),
                new LlmRouteScorer(),
                new MockEnvironment());

        RoutingEligibility eligibility = service.evaluate("openai-mini", cfg, "chat");
        String breadcrumb = eligibility.asBreadcrumb().toString();

        assertFalse(eligibility.eligible());
        assertTrue(eligibility.failureClasses().contains(LlmFailureClass.AUTH_MISSING));
        assertEquals("(redacted)", eligibility.safeMeta().get("credentialEnv"));
        assertEquals("missing_provider_api_key", eligibility.safeMeta().get("disabledReason"));
        assertFalse(breadcrumb.contains(rawToken), breadcrumb);
        assertFalse(breadcrumb.contains("routecredentialtoken"), breadcrumb);
    }
}
