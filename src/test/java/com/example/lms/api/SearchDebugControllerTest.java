package com.example.lms.api;

import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.routing.ApiRoutingPolicySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Plain unit test - no Spring context, no provider call. */
class SearchDebugControllerTest {

    private static final class StubProvider implements WebSearchProvider {
        private final ProviderId id;

        StubProvider(ProviderId id) {
            this.id = id;
        }

        @Override
        public WebSearchResult search(WebSearchQuery query) {
            throw new UnsupportedOperationException("dry-run test must never call a provider");
        }

        @Override
        public ProviderId id() {
            return id;
        }
    }

    @SuppressWarnings("unchecked")
    private static SearchDebugController controller(Environment env, List<WebSearchProvider> beans) {
        ObjectProvider<WebSearchProvider> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(beans == null ? Stream.empty() : beans.stream());
        return new SearchDebugController(
                new SearchDecisionService(),
                new ApiRoutingPolicySnapshot(env),
                new ProviderCredentialResolver(env),
                provider,
                env);
    }

    @Test
    void decisionIsADryRunEchoingTheServiceVerdict() {
        SearchDebugController c = controller(new MockEnvironment(), List.of());
        ResponseEntity<Map<String, Object>> res = c.decision(
                "latest spring boot release?", "FORCE_DEEP", null, 3, true);
        assertEquals(200, res.getStatusCode().value());
        Map<String, Object> out = res.getBody();
        assertNotNull(out);
        assertEquals(true, out.get("ok"));
        assertEquals(true, out.get("dryRun"));
        assertEquals(false, out.get("executed"));
        assertEquals("FORCE_DEEP", out.get("mode"));
        assertEquals(true, out.get("shouldSearch"));
        assertEquals("DEEP", out.get("depth"));
        assertEquals(3, out.get("topK"));
    }

    @Test
    void offModeReportsNoSearch() {
        SearchDebugController c = controller(new MockEnvironment(), List.of());
        ResponseEntity<Map<String, Object>> res = c.decision("anything", "OFF", null, null, true);
        assertEquals(200, res.getStatusCode().value());
        assertEquals(false, res.getBody().get("shouldSearch"));
    }

    @Test
    void unknownModeIsA400() {
        SearchDebugController c = controller(new MockEnvironment(), List.of());
        ResponseEntity<Map<String, Object>> res = c.decision("q", "BOGUS", null, null, true);
        assertEquals(400, res.getStatusCode().value());
        assertEquals("bad_mode", res.getBody().get("error"));
    }

    @Test
    void runtimeStatusMirrorsPolicyWithoutCallingProviders() {
        MockEnvironment env = new MockEnvironment();
        SearchDebugController c = controller(env, List.of(new StubProvider(ProviderId.NAVER)));
        ResponseEntity<Map<String, Object>> res = c.runtimeStatus();
        assertEquals(200, res.getStatusCode().value());
        Map<String, Object> out = res.getBody();
        assertNotNull(out);
        assertEquals(true, out.get("ok"));
        assertEquals(true, out.get("chainEnabled"));
        assertEquals(false, out.get("agentModeActive"));
        assertEquals(false, out.get("explicitPaidOverride"));

        List<?> routes = (List<?>) out.get("searchRoutes");
        assertNotNull(routes);
        assertTrue(routes.stream().anyMatch(r -> "naver".equals(((Map<?, ?>) r).get("id"))));

        List<?> providers = (List<?>) out.get("providers");
        assertNotNull(providers);
        Map<?, ?> naver = providers.stream()
                .map(p -> (Map<?, ?>) p)
                .filter(p -> "NAVER".equals(p.get("id")))
                .findFirst().orElse(null);
        assertNotNull(naver);
        assertEquals(true, naver.get("bean"));
        assertEquals("free_local", naver.get("policyTier"));
        assertEquals(false, naver.get("credentialPresent"));
        // The resolver must never surface a credential value on this surface.
        String json = out.toString();
        assertFalse(json.contains("valueOrNull"));
    }

    @Test
    void runtimeStatusMarksUnregisteredProviders() {
        SearchDebugController c = controller(new MockEnvironment(), List.of());
        ResponseEntity<Map<String, Object>> res = c.runtimeStatus();
        List<?> providers = (List<?>) res.getBody().get("providers");
        Map<?, ?> brave = providers.stream()
                .map(p -> (Map<?, ?>) p)
                .filter(p -> "BRAVE".equals(p.get("id")))
                .findFirst().orElse(null);
        assertNotNull(brave);
        assertEquals(false, brave.get("bean"));
        assertEquals("free_local", brave.get("policyTier"));
    }
}
