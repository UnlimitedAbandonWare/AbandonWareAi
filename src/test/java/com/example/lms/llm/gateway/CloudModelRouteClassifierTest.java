package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.llm.spec.CloudModelCatalogLoader;
import com.example.lms.llm.spec.CloudModelMetadataProbe;
import com.example.lms.llm.spec.ModelSpecSnapshot;
import com.example.lms.llm.spec.ModelSpecRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudModelRouteClassifierTest {

    @Test
    void classifiesCloudCatalogWithRouteScoreAndDisabledReasonWithoutOutboundCalls() {
        LlmGatewayProperties gatewayProps = new LlmGatewayProperties();
        gatewayProps.getSpecRegistry().setEnabled(false);
        LlmRouterProperties routerProps = new LlmRouterProperties();
        LlmRouterProperties.ModelConfig gemini = new LlmRouterProperties.ModelConfig();
        gemini.setEnabled(true);
        gemini.setProvider("gemini");
        gemini.setName("gemini-2.5-pro");
        gemini.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai");
        gemini.setFallbackOnly(true);
        gemini.setWeight(0.0d);
        gemini.setCredentialEnv("GEMINI_API_KEY");
        gemini.setMinContextTokens(1_048_576);
        routerProps.setModels(Map.of("gemini-pro", gemini));
        CloudModelRouteClassifier classifier = new CloudModelRouteClassifier(
                new CloudModelCatalogLoader(new CloudModelMetadataProbe()),
                routerProps,
                new HybridLlmGatewayProbeService(
                        gatewayProps,
                        new ModelRuntimeHealthTracker(),
                        new ModelSpecRegistry(new ObjectMapper(), gatewayProps),
                        new LlmRouteScorer(),
                        new MockEnvironment().withProperty("GEMINI_API_KEY", "changeme")));

        List<CloudModelRouteClassifier.CloudModelRouteRow> rows = classifier.classifyDefaultCatalog("chat");

        CloudModelRouteClassifier.CloudModelRouteRow row = row(rows, "gemini", "gemini-2.5-pro");
        assertEquals("gemini-pro", row.routeKey());
        assertEquals("gemini_api_openai_compatible", row.apiSurface());
        assertEquals(1_048_576, row.contextTokens());
        assertEquals(65_536, row.maxOutputTokens());
        assertTrue(row.capabilities().contains("multimodal"));
        assertEquals("GEMINI_API_KEY", row.credentialEnv());
        assertEquals("disabled_by_default_long_context_route", row.routingDecision());
        assertEquals(0, row.routeScore());
        assertFalse(row.eligible());
        assertEquals("cloud_manifest_disabled", row.disabledReason());
        assertEquals(List.of(LlmFailureClass.DISABLED), row.failureClasses());
        assertFalse(row.toString().contains("changeme"));
    }

    @Test
    void keepsAnthropicNativeModelOnHoldWithoutRouteAdapter() {
        LlmGatewayProperties gatewayProps = new LlmGatewayProperties();
        gatewayProps.getSpecRegistry().setEnabled(false);
        CloudModelRouteClassifier classifier = new CloudModelRouteClassifier(
                new CloudModelCatalogLoader(new CloudModelMetadataProbe()),
                new LlmRouterProperties(),
                new HybridLlmGatewayProbeService(
                        gatewayProps,
                        new ModelRuntimeHealthTracker(),
                        new ModelSpecRegistry(new ObjectMapper(), gatewayProps),
                        new LlmRouteScorer(),
                        new MockEnvironment().withProperty("ANTHROPIC_API_KEY", "real-looking-test-value")));

        List<CloudModelRouteClassifier.CloudModelRouteRow> rows = classifier.classifyDefaultCatalog("chat");

        CloudModelRouteClassifier.CloudModelRouteRow row = row(rows, "anthropic", "claude-current-family");
        assertEquals("hold_until_native_adapter_or_proxy_exists", row.routingDecision());
        assertEquals("route_not_configured", row.disabledReason());
        assertEquals(0, row.routeScore());
        assertFalse(row.eligible());
    }

    @Test
    void manifestDisabledWithoutConfiguredRouteStaysManualNotRouteMissing() {
        CloudModelRouteClassifier classifier = new CloudModelRouteClassifier(
                new CloudModelCatalogLoader(new CloudModelMetadataProbe()),
                new LlmRouterProperties(),
                null);

        List<CloudModelRouteClassifier.CloudModelRouteRow> rows = classifier.classifyDefaultCatalog("chat");

        CloudModelRouteClassifier.CloudModelRouteRow row = row(rows, "openai", "gpt-5.4-mini");
        assertNull(row.routeKey());
        assertFalse(row.eligible());
        assertEquals("disabled_by_default_paid_fallback", row.routingDecision());
        assertEquals("cloud_manifest_disabled", row.disabledReason());
    }

    @Test
    void manifestDisabledCatalogEntryBlocksRouteEvenWhenCredentialLooksUsable() {
        LlmGatewayProperties gatewayProps = new LlmGatewayProperties();
        gatewayProps.getSpecRegistry().setEnabled(false);
        LlmRouterProperties routerProps = new LlmRouterProperties();
        LlmRouterProperties.ModelConfig groq = new LlmRouterProperties.ModelConfig();
        groq.setEnabled(true);
        groq.setProvider("groq");
        groq.setName("openai/gpt-oss-120b");
        groq.setBaseUrl("https://api.groq.com/openai/v1");
        groq.setFallbackOnly(true);
        groq.setWeight(0.0d);
        routerProps.setModels(Map.of("api3", groq));
        CloudModelRouteClassifier classifier = new CloudModelRouteClassifier(
                new CloudModelCatalogLoader(new CloudModelMetadataProbe()),
                routerProps,
                new HybridLlmGatewayProbeService(
                        gatewayProps,
                        new ModelRuntimeHealthTracker(),
                        new ModelSpecRegistry(new ObjectMapper(), gatewayProps),
                        new LlmRouteScorer(),
                        new MockEnvironment().withProperty("GROQ_API_KEY", "real-looking-value-123")));

        List<CloudModelRouteClassifier.CloudModelRouteRow> rows = classifier.classifyDefaultCatalog("chat");

        CloudModelRouteClassifier.CloudModelRouteRow row = row(rows, "groq", "openai/gpt-oss-120b");
        assertEquals("api3", row.routeKey());
        assertEquals("metadata_for_existing_api3_fallback", row.routingDecision());
        assertEquals(0, row.routeScore());
        assertFalse(row.eligible());
        assertEquals("cloud_manifest_disabled", row.disabledReason());
        assertEquals(List.of(LlmFailureClass.DISABLED), row.failureClasses());
        assertFalse(row.toString().contains("real-looking-value-123"));
    }

    @Test
    void enabledCloudCatalogRouteStillBlocksDummyCredentialThroughGatewayProbe() {
        LlmGatewayProperties gatewayProps = new LlmGatewayProperties();
        gatewayProps.getSpecRegistry().setEnabled(false);
        LlmRouterProperties routerProps = new LlmRouterProperties();
        LlmRouterProperties.ModelConfig openai = new LlmRouterProperties.ModelConfig();
        openai.setEnabled(true);
        openai.setProvider("openai");
        openai.setName("gpt-5.4-mini");
        openai.setBaseUrl("https://api.openai.com/v1");
        openai.setFallbackOnly(true);
        openai.setWeight(0.0d);
        openai.setCredentialEnv("OPENAI_API_KEY");
        routerProps.setModels(Map.of("openai-mini", openai));
        CloudModelRouteClassifier classifier = new CloudModelRouteClassifier(
                new CloudModelCatalogLoader(new CloudModelMetadataProbe()),
                routerProps,
                new HybridLlmGatewayProbeService(
                        gatewayProps,
                        new ModelRuntimeHealthTracker(),
                        new ModelSpecRegistry(new ObjectMapper(), gatewayProps),
                        new LlmRouteScorer(),
                        new MockEnvironment().withProperty("OPENAI_API_KEY", "dummy")));
        ModelSpecSnapshot enabledSnapshot = ModelSpecSnapshot.of(
                "openai",
                "gpt-5.4-mini",
                "api.openai.com",
                400_000,
                null,
                List.of("chat", "reasoning", "code", "tools"),
                Map.of(
                        "enabled", true,
                        "credentialEnv", "OPENAI_API_KEY",
                        "apiSurface", "responses",
                        "maxOutputTokens", 128_000,
                        "routingDecision", "manual_enabled_paid_fallback"));

        List<CloudModelRouteClassifier.CloudModelRouteRow> rows = classifier.classify(List.of(enabledSnapshot), "chat");

        CloudModelRouteClassifier.CloudModelRouteRow row = row(rows, "openai", "gpt-5.4-mini");
        assertEquals("openai-mini", row.routeKey());
        assertEquals("responses", row.apiSurface());
        assertEquals(400_000, row.contextTokens());
        assertEquals(128_000, row.maxOutputTokens());
        assertEquals("OPENAI_API_KEY", row.credentialEnv());
        assertEquals("manual_enabled_paid_fallback", row.routingDecision());
        assertTrue(row.fallbackOnly());
        assertFalse(row.eligible());
        assertEquals(0, row.routeScore());
        assertTrue(row.failureClasses().contains(LlmFailureClass.AUTH_MISSING));
        assertEquals("missing OPENAI_API_KEY", row.disabledReason());
        assertFalse(row.toString().contains("dummy"));
    }

    @Test
    void malformedConfiguredRouteBaseUrlLeavesRedactedBreadcrumb() {
        TraceStore.clear();
        LlmGatewayProperties gatewayProps = new LlmGatewayProperties();
        gatewayProps.getSpecRegistry().setEnabled(false);
        LlmRouterProperties routerProps = new LlmRouterProperties();
        LlmRouterProperties.ModelConfig malformed = new LlmRouterProperties.ModelConfig();
        malformed.setEnabled(true);
        malformed.setProvider("not-gemini");
        malformed.setName("gemini-2.5-pro");
        String rawBaseUrl = "https://bad host.invalid/v1/private-route";
        malformed.setBaseUrl(rawBaseUrl);
        routerProps.setModels(Map.of("bad-gemini", malformed));
        CloudModelRouteClassifier classifier = new CloudModelRouteClassifier(
                new CloudModelCatalogLoader(new CloudModelMetadataProbe()),
                routerProps,
                new HybridLlmGatewayProbeService(
                        gatewayProps,
                        new ModelRuntimeHealthTracker(),
                        new ModelSpecRegistry(new ObjectMapper(), gatewayProps),
                        new LlmRouteScorer(),
                        new MockEnvironment()));

        List<CloudModelRouteClassifier.CloudModelRouteRow> rows = classifier.classifyDefaultCatalog("chat");

        CloudModelRouteClassifier.CloudModelRouteRow row = row(rows, "gemini", "gemini-2.5-pro");
        assertEquals("cloud_manifest_disabled", row.disabledReason());
        assertEquals(Boolean.TRUE, TraceStore.get("llm.gateway.cloud.routeClassifier.suppressed.endpointHost"));
        assertEquals("invalid_url", TraceStore.get("llm.gateway.cloud.routeClassifier.suppressed.endpointHost.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawBaseUrl));
    }

    private static CloudModelRouteClassifier.CloudModelRouteRow row(
            List<CloudModelRouteClassifier.CloudModelRouteRow> rows,
            String provider,
            String model) {
        return rows.stream()
                .filter(row -> provider.equals(row.provider()) && model.equals(row.modelId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing cloud route row " + provider + ":" + model));
    }
}
