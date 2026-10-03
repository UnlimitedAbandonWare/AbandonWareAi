package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.guard.KeyResolver;
import com.example.lms.telemetry.SseEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Route/spend/credential facts must reach {@link DebugEventStore} through the
 * {@code where} contract: {@code api.route.decision}, {@code api.route.attempt},
 * {@code api.spend.decision}, {@code api.credential.resolved}. Event emission
 * must not depend on the INFO log level and must never treat eligibility or a
 * credential lookup as an actual route decision.
 */
class RoutingDebugEventBridgeTest {

    private static final Set<String> ROUTE_FIELDS = Set.of(
            "purpose", "provider", "model", "endpointClass", "attempt",
            "httpStatus", "errorClass", "fallbackTo", "keyPresent", "keySource");

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DebugEventStore> storeProvider(DebugEventStore store) {
        ObjectProvider<DebugEventStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<LlmRouterProperties> propsProvider(LlmRouterProperties props) {
        ObjectProvider<LlmRouterProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(props);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SseEventPublisher> sseProvider() {
        ObjectProvider<SseEventPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(SseEventPublisher.class));
        return provider;
    }

    private static DebugEventStore store() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        return store;
    }

    private static LlmRouterProperties routerProps() {
        LlmRouterProperties props = new LlmRouterProperties();
        LlmRouterProperties.ModelConfig local = new LlmRouterProperties.ModelConfig();
        local.setProvider("local");
        local.setName("qwen3:8b");
        local.setBaseUrl("http://127.0.0.1:11435/v1");
        LlmRouterProperties.ModelConfig cloud = new LlmRouterProperties.ModelConfig();
        cloud.setProvider("groq");
        cloud.setName("qwen/qwen3-32b");
        cloud.setBaseUrl("https://api.groq.com/openai/v1");
        props.setModels(Map.of("local-a", local, "api3", cloud));
        return props;
    }

    private static Set<String> routeDataKeys(DebugEvent event) {
        return event.data().keySet().stream()
                .filter(k -> !k.equals("kind") && !k.startsWith("ctx."))
                .collect(Collectors.toSet());
    }

    private static List<DebugEvent> where(DebugEventStore store, String where) {
        return store.list(100).stream().filter(e -> where.equals(e.where())).toList();
    }

    @Test
    void fallbackEmitsRouteDecisionWithAllowlistFields() {
        DebugEventStore store = store();
        LlmGatewayBreadcrumbPublisher publisher = new LlmGatewayBreadcrumbPublisher(
                sseProvider(), propsProvider(routerProps()), storeProvider(store));

        publisher.publishFallback("local-a", "api3", LlmFailureClass.TIMEOUT_SOFT, "local_provider_unavailable");

        List<DebugEvent> decisions = where(store, "api.route.decision");
        assertEquals(1, decisions.size());
        Map<String, Object> data = decisions.get(0).data();
        assertTrue(ROUTE_FIELDS.containsAll(routeDataKeys(decisions.get(0))),
                "route payload must stay inside the debug.fields allowlist: " + data.keySet());
        assertEquals("local", data.get("provider"));
        assertEquals("groq", data.get("fallbackTo"));
        assertEquals("timeout_soft", data.get("errorClass"));
        assertFalse(data.containsKey("why_code"), "why_code never belongs to a route payload");
    }

    @Test
    void failureEmitsRouteAttemptNotDecision() {
        DebugEventStore store = store();
        LlmGatewayBreadcrumbPublisher publisher = new LlmGatewayBreadcrumbPublisher(
                sseProvider(), propsProvider(routerProps()), storeProvider(store));

        publisher.publishFailure("local-a", LlmFailureClass.PROVIDER_ERROR, new RuntimeException("boom"));

        assertEquals(1, where(store, "api.route.attempt").size());
        assertEquals(0, where(store, "api.route.decision").size(),
                "a failed attempt is not a successful route decision");
    }

    @Test
    void eligibilityNeverReachesTheDebugEventStore() {
        DebugEventStore store = store();
        LlmGatewayBreadcrumbPublisher publisher = new LlmGatewayBreadcrumbPublisher(
                sseProvider(), propsProvider(routerProps()), storeProvider(store));

        publisher.publishEligibility(new RoutingEligibility(
                "api3", "groq", "qwen/qwen3-32b", "chat", 80, true, false, List.of(), Map.of()));

        assertTrue(store.list(100).isEmpty(),
                "candidate eligibility is not an actual route decision");
    }

    @Test
    void routeEventsEmitWhenInfoLoggingDisabled() {
        Logger jul = Logger.getLogger("com.example.lms.routing.ApiRoutingDebug");
        Level prior = jul.getLevel();
        jul.setLevel(Level.OFF);
        try {
            DebugEventStore store = store();
            LlmGatewayBreadcrumbPublisher publisher = new LlmGatewayBreadcrumbPublisher(
                    sseProvider(), propsProvider(routerProps()), storeProvider(store));
            publisher.publishFallback("local-a", "api3", LlmFailureClass.TIMEOUT_SOFT, "x");
            publisher.publishDecision("llm", "groq", "qwen/qwen3-32b", "api.groq.com",
                    1, 200, null, null, Boolean.TRUE, "GROQ_API_KEY");
            assertFalse(where(store, "api.route.decision").isEmpty(),
                    "route events must not depend on INFO logging");
        } finally {
            jul.setLevel(prior);
        }
    }

    @Test
    void publishDecisionCarriesAttemptAndHttpStatus() {
        DebugEventStore store = store();
        LlmGatewayBreadcrumbPublisher publisher = new LlmGatewayBreadcrumbPublisher(
                sseProvider(), propsProvider(routerProps()), storeProvider(store));

        publisher.publishDecision("llm", "groq", "qwen/qwen3-32b", "api.groq.com",
                2, 200, null, null, Boolean.TRUE, "GROQ_API_KEY");

        List<DebugEvent> decisions = where(store, "api.route.decision");
        assertEquals(1, decisions.size());
        Map<String, Object> data = decisions.get(0).data();
        assertTrue(ROUTE_FIELDS.containsAll(routeDataKeys(decisions.get(0))));
        assertEquals(2, data.get("attempt"));
        assertEquals(200, data.get("httpStatus"));
        assertEquals(Boolean.TRUE, data.get("keyPresent"));
    }

    @Test
    void spendDecisionUsesSeparateProjection() {
        DebugEventStore store = store();
        LlmGatewayBreadcrumbPublisher publisher = new LlmGatewayBreadcrumbPublisher(
                sseProvider(), propsProvider(routerProps()), storeProvider(store));

        publisher.publishSpendDecision("verify", "groq", "qwen/qwen3-32b",
                "verification_required", true);

        List<DebugEvent> spends = where(store, "api.spend.decision");
        assertEquals(1, spends.size());
        Map<String, Object> data = spends.get(0).data();
        assertEquals("verification_required", data.get("why_code"));
        assertEquals(Boolean.TRUE, data.get("allowed"));
        assertEquals(0, where(store, "api.route.decision").size(),
                "spend facts never masquerade as route decisions");
    }

    @Test
    void credentialLookupEmitsCredentialNotRoute() {
        DebugEventStore store = store();
        KeyResolver resolver = new KeyResolver(new MockEnvironment(), storeProvider(store));

        resolver.resolveGroqApiKeyStrict();

        List<DebugEvent> credentials = where(store, "api.credential.resolved");
        assertEquals(1, credentials.size());
        Map<String, Object> data = credentials.get(0).data();
        assertEquals("groq", data.get("provider"));
        assertEquals(Boolean.FALSE, data.get("keyPresent"));
        assertEquals(0, where(store, "api.route.decision").size());
        assertEquals(0, where(store, "api.route.attempt").size());
    }
}
