package com.example.lms.llm;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.CloudModelRouteClassifier;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.RoutingEligibility;
import com.example.lms.llm.spec.CloudModelCatalogLoader;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatModelCatalogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RouteCatalogRegistrationTest {
    @AfterEach void clear() { TraceStore.clear(); }

    private DynamicChatModelFactory factory(ChatModelCatalogService catalog, LlmRouterProperties props) {
        var env = new MockEnvironment();
        var f = new DynamicChatModelFactory(env, new KeyResolver(env));
        ReflectionTestUtils.setField(f, "llmRouterProperties", props);
        // Absence at RED is part of the catalogue/registration seam, not a reflective test error.
        if (ReflectionUtils.findField(DynamicChatModelFactory.class, "chatModelCatalogService") != null)
            ReflectionTestUtils.setField(f, "chatModelCatalogService", catalog);
        ReflectionTestUtils.setField(f, "localBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(f, "highLocalBaseUrl", "http://127.0.0.1:11435/v1");
        ReflectionTestUtils.setField(f, "judgeLocalBaseUrl", "http://127.0.0.1:11438/v1");
        ReflectionTestUtils.setField(f, "localApiKey", "ollama");
        return f;
    }
    private ChatModelCatalogService cached(List<ChatModelCatalogService.Choice> rows) {
        var catalog = mock(ChatModelCatalogService.class);
        when(catalog.observedServerChoices()).thenReturn(rows);
        return catalog;
    }
    private ChatModelCatalogService.Choice local(String id, boolean ready) {
        return new ChatModelCatalogService.Choice(id, "Ollama", "local-default", id,
                ready ? "installed" : "unavailable", ready, ready ? "" : "capability_not_observed",
                "unknown", "installed_chat_capability");
    }

    @Test void allSelectableObservedCatalogueIdsResolveProvider() throws Exception {
        var rows = new ObjectMapper().readValue(getClass().getResourceAsStream("/route-identity/catalog-selectable.json"),
                new TypeReference<List<ChatModelCatalogService.Choice>>() {});
        assertFalse(rows.isEmpty());
        var props = new LlmRouterProperties();
        for (var row : rows) if (row.id().startsWith("llmrouter.")) {
            var cfg = new LlmRouterProperties.ModelConfig();
            cfg.setName(row.modelId()); cfg.setProvider(row.provider());
            props.getModels().put(row.endpointId(), cfg);
        }
        var catalog = cached(rows);
        var f = factory(catalog, props);
        for (var row : rows) assertEquals(row.provider().toLowerCase(Locale.ROOT),
                ReflectionTestUtils.invokeMethod(f, "registeredProvider", row.id()), row.id());
        verify(catalog, never()).choices();
    }

    @Test void realLocalCatalogueCacheRegistersChatWithoutAnotherDiscovery() {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var builder = mock(RestTemplateBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(http);
        server.expect(requestTo("http://localhost:11434/api/tags")).andRespond(withSuccess(
                "{\"models\":[{\"name\":\"fixture:chat\"},{\"name\":\"fixture:embed\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:11434/api/show")).andExpect(content().json("{\"model\":\"fixture:chat\"}"))
                .andRespond(withSuccess("{\"capabilities\":[\"completion\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:11434/api/show")).andExpect(content().json("{\"model\":\"fixture:embed\"}"))
                .andRespond(withSuccess("{\"capabilities\":[\"embedding\"]}", MediaType.APPLICATION_JSON));
        var catalog = new ChatModelCatalogService(null, null, builder, "http://localhost:11434/v1", false);
        assertEquals(1, catalog.choices().size());
        var f = factory(catalog, new LlmRouterProperties());
        assertEquals("ollama", ReflectionTestUtils.invokeMethod(f, "registeredProvider", "fixture:chat"));
        assertNull(ReflectionTestUtils.invokeMethod(f, "registeredProvider", "fixture:embed"));
        server.verify();
    }

    @Test void realCatalogueSelectableIdsResolveUsingSharedRouterConfiguration() {
        var props = new LlmRouterProperties();
        var cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("fixture:cloud-chat");
        cfg.setProvider("groq");
        cfg.setBaseUrl("https://fixture.invalid/v1");
        props.getModels().put("fixture-cloud", cfg);

        var loader = mock(CloudModelCatalogLoader.class);
        when(loader.loadDefaultCatalog()).thenReturn(List.of());
        var gateway = mock(HybridLlmGatewayProbeService.class);
        when(gateway.evaluate("fixture-cloud", cfg, "chat")).thenReturn(
                RoutingEligibility.eligible("fixture-cloud", "groq", "fixture:cloud-chat", "chat",
                        100, false, Map.of("capabilities", List.of("completion"))));
        var cloud = new CloudModelRouteClassifier(loader, props, gateway);

        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var builder = mock(RestTemplateBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(http);
        server.expect(requestTo("http://localhost:11434/api/tags")).andRespond(withSuccess(
                "{\"models\":[{\"name\":\"fixture:new-chat\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:11434/api/show"))
                .andExpect(content().json("{\"model\":\"fixture:new-chat\"}"))
                .andRespond(withSuccess("{\"capabilities\":[\"completion\"]}", MediaType.APPLICATION_JSON));
        var oauth = mock(ChatGptOAuthRegistration.class);
        when(oauth.models("fixture-owner")).thenReturn(List.of("fixture-account-chat"));
        var catalog = new ChatModelCatalogService(
                cloud, null, builder, "http://localhost:11434/v1", true);
        ReflectionTestUtils.setField(catalog, "chatGptOAuth", oauth);

        var selectable = catalog.choices("fixture-owner").stream()
                .filter(ChatModelCatalogService.Choice::selectable).toList();
        assertEquals(Set.of("fixture:new-chat", "llmrouter.fixture-cloud",
                        "chatgpt-oauth:fixture-account-chat"),
                selectable.stream().map(ChatModelCatalogService.Choice::id)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(3, selectable.size());
        var f = factory(catalog, props);
        for (var row : selectable) assertEquals(row.provider().toLowerCase(Locale.ROOT),
                ReflectionTestUtils.invokeMethod(f, "registeredProvider", row.id()), row.id());

        server.verify();
        verify(loader).loadDefaultCatalog();
        verify(gateway).evaluate("fixture-cloud", cfg, "chat");
        verify(oauth).models("fixture-owner");
        verifyNoMoreInteractions(loader, gateway, oauth);
    }

    @Test void admittedInventoryUsesItsDiscoveryEndpointBeforeRoleNameHeuristics() {
        var f = factory(cached(List.of(local("qwen3:30b", true))), new LlmRouterProperties());
        assertEquals("http://127.0.0.1:11434/v1",
                ReflectionTestUtils.invokeMethod(f, "selectLocalBaseUrl", "qwen3:30b"));
    }

    @Test void aliasChainTrimsAndSendsTheConfiguredWireModel() {
        var props = new LlmRouterProperties();
        var cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("fixture:chat"); cfg.setProvider("ollama"); cfg.setBaseUrl("http://127.0.0.1:11434/v1");
        props.getModels().put("registered", cfg);
        props.setAliases(Map.of("first", " second ", "second", " llmrouter.registered "));
        var f = factory(cached(List.of()), props);
        assertEquals("ollama", ReflectionTestUtils.invokeMethod(f, "registeredProvider", " FIRST "));
        var client = f.lcWithTimeout(" FIRST ", 0.3d, 0.7d, null, null, 16, 1, 0);
        assertEquals("fixture:chat", DynamicChatModelFactory.configuredModelId(client));
    }

    @Test void unavailableInventoryAndCloudRowsDoNotAuthorizeUnknownModels() {
        var cloud = new ChatModelCatalogService.Choice("cloud:fixture", "groq", "unconfigured",
                "fixture", "configured", true, "", "unknown", "fixture");
        var f = factory(cached(List.of(local("fixture:unavailable", false), cloud)), new LlmRouterProperties());
        for (var id : List.of("fixture:unavailable", "fixture:embed", "cloud:fixture", "unknown:model"))
            assertNull(ReflectionTestUtils.invokeMethod(f, "registeredProvider", id), id);
        assertEquals("route_unknown", assertThrows(LlmGatewayException.class,
                () -> ReflectionTestUtils.invokeMethod(f, "isLocalModel", "unknown:model")).reasonCode());
    }

    @Test void directExactLogicalRouteStillRequiresTheAspectBoundary() {
        var f = factory(cached(List.of()), new LlmRouterProperties());
        RequestedModelSelection.begin("llmrouter.registered");
        assertEquals("provider_not_configured", assertThrows(ModelSelectionException.class,
                () -> f.lcWithTimeout("llmrouter.registered", null, null, 16, 1)).code());
    }

    @Test void aliasCyclesRemainUnknownAndDoNotLoop() {
        var props = new LlmRouterProperties(); props.setAliases(Map.of("cycle-a", "cycle-b", "cycle-b", "cycle-a"));
        var f = factory(cached(List.of()), props);
        assertNull(ReflectionTestUtils.invokeMethod(f, "registeredProvider", "cycle-a"));
    }
}
