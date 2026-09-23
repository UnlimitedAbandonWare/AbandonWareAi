package com.example.lms.ensemble;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.RoutingEligibility;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiTriadRoutePreflightTest {

    @Test
    void threeDistinctReadyApiRoutesAreAcceptedWithoutCallingProviders() {
        LlmRouterProperties properties = readyProperties();
        properties.setFallbackWhenOpenAiMissing(false);
        HybridLlmGatewayProbeService probe = acceptingProbe();
        KeyResolver keys = new KeyResolver(new MockEnvironment()
                .withProperty("OPENAI_API_KEY", "unit-openai-secret-value")
                .withProperty("GROQ_API_KEY", "unit-groq-secret-value")
                .withProperty("GEMINI_API_KEY", "unit-gemini-secret-value"));
        ApiTriadRoutePreflight preflight = new ApiTriadRoutePreflight(properties, probe, keys);

        ApiTriadRoutePreflight.Result result = preflight.evaluate(List.of(
                "llmrouter.openai-premium",
                "llmrouter.api3",
                "llmrouter.gemini-pro"));

        assertTrue(result.ready());
        assertEquals(List.of("openai", "groq", "gemini"), result.providers());
    }

    @Test
    void missingCredentialAndRouterFallbackFailClosedForTheWholeTriad() {
        LlmRouterProperties properties = readyProperties();
        HybridLlmGatewayProbeService probe = acceptingProbe();
        KeyResolver missingGemini = new KeyResolver(new MockEnvironment()
                .withProperty("OPENAI_API_KEY", "unit-openai-secret-value")
                .withProperty("GROQ_API_KEY", "unit-groq-secret-value"));
        ApiTriadRoutePreflight preflight = new ApiTriadRoutePreflight(properties, probe, missingGemini);

        ApiTriadRoutePreflight.Result fallbackDenied = preflight.evaluate(List.of(
                "llmrouter.openai-premium", "llmrouter.api3", "llmrouter.gemini-pro"));
        assertFalse(fallbackDenied.ready());
        assertEquals("router_fallback_forbidden", fallbackDenied.reasonCode());

        properties.setFallbackWhenOpenAiMissing(false);
        ApiTriadRoutePreflight.Result missingKey = preflight.evaluate(List.of(
                "llmrouter.openai-premium", "llmrouter.api3", "llmrouter.gemini-pro"));
        assertFalse(missingKey.ready());
        assertEquals("credential_missing", missingKey.reasonCode());
    }

    @Test
    void providerHostMismatchFailsClosed() {
        LlmRouterProperties properties = readyProperties();
        properties.setFallbackWhenOpenAiMissing(false);
        properties.getModels().get("api3").setBaseUrl("https://api.openai.com/v1");
        ApiTriadRoutePreflight preflight = new ApiTriadRoutePreflight(
                properties,
                acceptingProbe(),
                new KeyResolver(new MockEnvironment()
                        .withProperty("OPENAI_API_KEY", "unit-openai-secret-value")
                        .withProperty("GROQ_API_KEY", "unit-groq-secret-value")
                        .withProperty("GEMINI_API_KEY", "unit-gemini-secret-value")));

        ApiTriadRoutePreflight.Result result = preflight.evaluate(List.of(
                "llmrouter.openai-premium", "llmrouter.api3", "llmrouter.gemini-pro"));

        assertFalse(result.ready());
        assertEquals("provider_endpoint_mismatch", result.reasonCode());
    }

    @Test
    void providerHostSuffixLookalikeFailsClosed() {
        LlmRouterProperties properties = readyProperties();
        properties.setFallbackWhenOpenAiMissing(false);
        properties.getModels().get("openai-premium")
                .setBaseUrl("https://api.openai.com.evil.example/v1");
        ApiTriadRoutePreflight preflight = new ApiTriadRoutePreflight(
                properties,
                acceptingProbe(),
                new KeyResolver(new MockEnvironment()
                        .withProperty("OPENAI_API_KEY", "unit-openai-secret-value")
                        .withProperty("GROQ_API_KEY", "unit-groq-secret-value")
                        .withProperty("GEMINI_API_KEY", "unit-gemini-secret-value")));

        ApiTriadRoutePreflight.Result result = preflight.evaluate(List.of(
                "llmrouter.openai-premium", "llmrouter.api3", "llmrouter.gemini-pro"));

        assertFalse(result.ready());
        assertEquals("provider_endpoint_mismatch", result.reasonCode());
    }

    @Test
    void malformedExternalRouteDetailsFailClosed() {
        List<String> malformed = List.of(
                "https://user:masked@api.openai.com/v1",
                "https://api.openai.com/v1?mode=masked",
                "https://api.openai.com/v1#fragment",
                "https://api.openai.com:444/v1",
                "https://api.openai.com/not-v1");

        for (String baseUrl : malformed) {
            LlmRouterProperties properties = readyProperties();
            properties.setFallbackWhenOpenAiMissing(false);
            properties.getModels().get("openai-premium").setBaseUrl(baseUrl);
            ApiTriadRoutePreflight preflight = new ApiTriadRoutePreflight(
                    properties,
                    acceptingProbe(),
                    new KeyResolver(new MockEnvironment()
                            .withProperty("OPENAI_API_KEY", "unit-openai-secret-value")
                            .withProperty("GROQ_API_KEY", "unit-groq-secret-value")
                            .withProperty("GEMINI_API_KEY", "unit-gemini-secret-value")));

            ApiTriadRoutePreflight.Result result = preflight.evaluate(List.of(
                    "llmrouter.openai-premium", "llmrouter.api3", "llmrouter.gemini-pro"));

            assertFalse(result.ready(), baseUrl);
            assertEquals("invalid_endpoint", result.reasonCode(), baseUrl);
        }
    }

    private static HybridLlmGatewayProbeService acceptingProbe() {
        HybridLlmGatewayProbeService probe = mock(HybridLlmGatewayProbeService.class);
        when(probe.cloudFallbackEnabled()).thenReturn(false);
        when(probe.evaluate(anyString(), any(), anyString())).thenAnswer(invocation -> {
            String route = invocation.getArgument(0);
            LlmRouterProperties.ModelConfig cfg = invocation.getArgument(1);
            return RoutingEligibility.eligible(route, cfg.getProvider(), cfg.getName(), "chat", 100,
                    cfg.isFallbackOnly(), Map.of());
        });
        return probe;
    }

    private static LlmRouterProperties readyProperties() {
        LlmRouterProperties properties = new LlmRouterProperties();
        properties.setModels(Map.of(
                "openai-premium", route("openai", "gpt-5.5", "https://api.openai.com/v1"),
                "api3", route("groq", "openai/gpt-oss-120b", "https://api.groq.com/openai/v1"),
                "gemini-pro", route("gemini", "gemini-2.5-pro",
                        "https://generativelanguage.googleapis.com/v1beta/openai")));
        return properties;
    }

    private static LlmRouterProperties.ModelConfig route(String provider, String model, String baseUrl) {
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(true);
        cfg.setProvider(provider);
        cfg.setName(model);
        cfg.setBaseUrl(baseUrl);
        cfg.setFallbackOnly(true);
        return cfg;
    }
}
