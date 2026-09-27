package com.example.lms.agent.context;

import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProviderStatusProjectionTest {

    private static final Set<String> PUBLIC_FIELDS = Set.of(
            "provider", "route", "model", "enabled", "credentialPresent", "attemptCount",
            "statusCode", "latencyMs", "cacheHit", "quotaDecision", "fallbackReason", "errorClass");

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void projectsOnlyTheTwelveProviderStatusFieldsFromAnObservedGatewayStatus() {
        TraceStore.put("provider.status.gemini.provider", "gemini");
        TraceStore.put("provider.status.gemini.route", "search-expansion");
        TraceStore.put("provider.status.gemini.model", "gemini-2.5-flash");
        TraceStore.put("provider.status.gemini.enabled", true);
        TraceStore.put("provider.status.gemini.credentialPresent", true);
        TraceStore.put("provider.status.gemini.attemptCount", 1);
        TraceStore.put("provider.status.gemini.statusCode", 200);
        TraceStore.put("provider.status.gemini.latencyMs", 37L);
        TraceStore.put("provider.status.gemini.cacheHit", false);
        TraceStore.put("provider.status.gemini.quotaDecision", "allowed");
        TraceStore.put("provider.status.gemini.fallbackReason", "none");
        TraceStore.put("provider.status.gemini.errorClass", "none");
        TraceStore.put("provider.status.gemini.rawPrompt", "private prompt must not render");
        TraceStore.put("provider.status.gemini.authorization", "Bearer private-token");

        AgentPipelineHealthController controller = controllerWith(new MockEnvironment());
        Map<String, Object> health = controller.pipelineHealth();
        Map<String, Object> gemini = providerStatus(health, "gemini");

        assertEquals(PUBLIC_FIELDS, gemini.keySet());
        assertEquals("search-expansion", gemini.get("route"));
        assertEquals("gemini-2.5-flash", gemini.get("model"));
        assertEquals(1, gemini.get("attemptCount"));
        assertEquals(200, gemini.get("statusCode"));
        assertEquals(37L, gemini.get("latencyMs"));
        assertFalse(health.toString().contains("private prompt must not render"));
        assertFalse(health.toString().contains("Bearer private-token"));
    }

    @Test
    void configOnlyStatusIsHonestWhenNoWireAttemptWasObserved() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("BRAVE_API_KEY", "brave-value")
                .withProperty("NAVER_CLIENT_ID", "naver-id")
                .withProperty("NAVER_CLIENT_SECRET", "naver-secret");

        Map<String, Object> health = controllerWith(environment).pipelineHealth();
        Map<String, Object> brave = providerStatus(health, "brave");
        Map<String, Object> naver = providerStatus(health, "naver");
        Map<String, Object> gemini = providerStatus(health, "gemini");

        assertEquals(Boolean.TRUE, brave.get("enabled"));
        assertEquals(Boolean.TRUE, brave.get("credentialPresent"));
        assertEquals(0, brave.get("attemptCount"));
        assertEquals("not_observed", brave.get("statusCode"));
        assertEquals("not_observed", brave.get("quotaDecision"));
        assertEquals(Boolean.TRUE, naver.get("enabled"));
        assertEquals(Boolean.FALSE, gemini.get("enabled"));
        assertEquals(Boolean.FALSE, gemini.get("credentialPresent"));
        assertEquals("missing-credential", gemini.get("fallbackReason"));
        assertFalse(health.toString().contains("brave-value"));
        assertFalse(health.toString().contains("naver-secret"));
    }

    @Test
    void braveConfigOffIsDisabledEvenWhenCredentialIsPresent() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("BRAVE_API_KEY", "brave-disabled-config-value");
        AgentPipelineHealthController controller = controllerWith(environment);
        ReflectionTestUtils.setField(controller, "braveConfigEnabled", false);

        Map<String, Object> brave = providerStatus(controller.pipelineHealth(), "brave");

        assertEquals(Boolean.FALSE, brave.get("enabled"));
        assertEquals(Boolean.TRUE, brave.get("credentialPresent"));
        assertEquals("disabled_by_config", brave.get("fallbackReason"));
    }

    @Test
    void legacyWebProviderRowsUseResolverConflictStateWithoutExposingValues() {
        String first = "brave-health-secret-a";
        String second = "brave-health-secret-b";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("gpt-search.brave.subscription-token", first)
                .withProperty("BRAVE_API_KEY", second);

        Map<String, Object> health = controllerWith(environment).pipelineHealth();
        Map<String, Object> brave = webProvider(health, "brave");

        assertEquals(Boolean.FALSE, brave.get("hasKey"));
        assertEquals("DISABLED", brave.get("status"));
        assertEquals("conflict", brave.get("keySource"));
        assertEquals("conflicting-credential-aliases", brave.get("disabledReason"));
        assertFalse(health.toString().contains(first));
        assertFalse(health.toString().contains(second));
    }

    private static AgentPipelineHealthController controllerWith(MockEnvironment environment) {
        AgentPipelineHealthController controller = new AgentPipelineHealthController(mock(AgentDbContextProvider.class));
        ReflectionTestUtils.setField(controller, "providerCredentialResolver",
                new ProviderCredentialResolver(environment));
        return controller;
    }

    private static Map<String, Object> providerStatus(Map<String, Object> health, String provider) {
        Object value = health.get("providerStatus");
        assertTrue(value instanceof List<?>);
        return ((List<?>) value).stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .filter(row -> provider.equals(row.get("provider")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> webProvider(Map<String, Object> health, String provider) {
        Object value = health.get("webProviders");
        assertTrue(value instanceof List<?>);
        return ((List<?>) value).stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .filter(row -> provider.equals(row.get("provider")))
                .findFirst()
                .orElseThrow();
    }
}
