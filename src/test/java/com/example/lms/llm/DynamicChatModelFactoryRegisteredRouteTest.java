package com.example.lms.llm;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.gateway.LlmGatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicChatModelFactoryRegisteredRouteTest {
    private DynamicChatModelFactory factory(String provider, String name, String endpoint, KeyResolver keys) {
        var env = new MockEnvironment();
        var factory = new DynamicChatModelFactory(env, keys == null ? new KeyResolver(env) : keys);
        var config = new LlmRouterProperties.ModelConfig();
        config.setProvider(provider); config.setName(name); config.setBaseUrl(endpoint);
        var props = new LlmRouterProperties(); props.setModels(Map.of("registered", config));
        ReflectionTestUtils.setField(factory, "llmRouterProperties", props);
        ReflectionTestUtils.setField(factory, "localBaseUrl", "http://127.0.0.1:11435/v1");
        ReflectionTestUtils.setField(factory, "highLocalBaseUrl", "http://127.0.0.1:11434/v1");
        return factory;
    }

    @Test void geminiRouteIsNeverClassifiedAsLocal() {
        var factory = factory("gemini", "ambiguous:cloud", "https://example.invalid/v1", null);
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(factory, "isLocalModel", "ambiguous:cloud"));
    }
    @Test void unknownModelDoesNotDefaultToOllama() {
        var factory = factory("local", "installed:chat", "http://127.0.0.1:11434/v1", null);
        var failure = assertThrows(LlmGatewayException.class,
                () -> factory.lcWithTimeout("unregistered-model", null, null, 16, 1));
        assertEquals("route_unknown", failure.reasonCode());
    }
    @Test void routeProviderDeterminesCredentialResolver() {
        var keys = mock(KeyResolver.class);
        var factory = factory("gemini", "ambiguous:cloud", "https://example.invalid/v1", keys);
        assertFalse(factory.canServe("ambiguous:cloud"));
        verify(keys).resolveGeminiApiKeyStrict();
        verify(keys, never()).resolveOpenAiApiKeyStrict();
    }
    @Test void localDeviceRoleSelectsExpectedEndpoint() {
        var factory = factory("local", "arbitrary-installed-tag", null, null);
        LlmRouterProperties props = (LlmRouterProperties) ReflectionTestUtils.getField(factory, "llmRouterProperties");
        props.getModels().get("registered").setDeviceRole("rtx3090");
        assertEquals("http://127.0.0.1:11434/v1",
                ReflectionTestUtils.invokeMethod(factory, "selectLocalBaseUrl", "arbitrary-installed-tag"));
    }
    @Test void unconfiguredRemoteRouteIsNotSelectable() {
        assertFalse(factory("gemini", "remote-name", "https://example.invalid/v1", null).canServe("remote-name"));
    }
    @Test void chatgptOauthRouteResolvesAsRemote() {
        var factory = factory("local", "installed:chat", "http://127.0.0.1:11434/v1", null);
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(factory, "isLocalModel", "chatgpt-oauth:synthetic"));
    }
    @Test void automaticRouteNeverRequestsLocalWarmup() {
        var factory = factory("local", "installed:chat", "http://127.0.0.1:11434/v1", null);
        var manager = mock(com.example.lms.config.LocalLlmProcessManager.class);
        var gateway = mock(com.example.lms.llm.gateway.HybridLlmGatewayProbeService.class);
        ReflectionTestUtils.setField(factory, "localLlmProcessManager", manager);
        ReflectionTestUtils.setField(factory, "localGatewayProbe", gateway);
        assertNull(factory.requestModelWarmup("llmrouter.auto"));
        verifyNoInteractions(manager, gateway);
    }
}
