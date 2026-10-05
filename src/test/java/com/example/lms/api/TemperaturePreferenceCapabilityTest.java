package com.example.lms.api;

import com.example.lms.config.ChatDefaultsProperties;
import com.example.lms.service.ChatPreferenceService;
import com.example.lms.service.SettingsService;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.guard.KeyResolver;
import ai.abandonware.nova.config.LlmRouterProperties;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class TemperaturePreferenceCapabilityTest {
    @Test
    void preferencesWithoutAnObservedRoutePreserveRequestedZeroAndExposeUnknownSupport() {
        var service = mock(ChatPreferenceService.class);
        var settings = mock(SettingsService.class);
        var owner = mock(ClientOwnerKeyResolver.class);
        when(owner.ownerKey()).thenReturn("00000000-0000-0000-0000-000000000001");
        var state = new ChatPreferenceService.State(Map.of("temperature", 0.0), 7, "a".repeat(64));
        when(service.read(anyString())).thenReturn(state);
        when(settings.getChatAdminOverrides()).thenReturn(Map.of());
        var defaults = defaults();
        var controller = new ChatPreferencesController(service, settings, defaults, owner);
        var response = controller.get();
        var json = new ObjectMapper().valueToTree(response.getBody());
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UNKNOWN", json.path("sampling").path("temperature").path("support").asText());
        assertEquals(0.0, json.path("overrides").path("temperature").asDouble());
        assertEquals(7, json.path("revision").asLong());
        verify(service, never()).patch(anyString(), anyMap(), anyList(), anyLong(), any());
    }

    private static ChatDefaultsProperties defaults() {
        var defaults = new ChatDefaultsProperties();
        defaults.setModel("chatgpt-oauth:gpt-5.5"); defaults.setModelSelectionMode("preferred");
        defaults.setTemperature(0.3); defaults.setTopP(1.0);
        defaults.setFrequencyPenalty(0.0); defaults.setPresencePenalty(0.0); defaults.setMaxTokens(2048);
        defaults.setUseRag(true); defaults.setUseWebSearch(false); defaults.setSearchMode("OFF");
        defaults.setRagAnswerPolicy("adaptive"); defaults.setDefaultsVersion("fixture");
        return defaults;
    }

    @Test
    void capabilityIsConfigOnlyAndExactRoutesDoNotInferFinalReasoning() {
        var keys = mock(KeyResolver.class);
        var factory = new DynamicChatModelFactory(new MockEnvironment(), keys);
        var router = new LlmRouterProperties();
        ReflectionTestUtils.setField(factory, "llmRouterProperties", router);
        assertEquals(ModelCapabilities.Support.NO, factory.temperatureCapability("chatgpt-oauth:gpt-5.5").support());
        assertEquals(ModelCapabilities.Support.UNKNOWN, factory.temperatureCapability("unknown:id").support());
        for (String name : new String[]{"gpt-5", "gpt-5-mini", "gpt-5.1", "gpt-5.2", "gpt-5.5"}) {
            var route = new LlmRouterProperties.ModelConfig();
            route.setName(name); route.setProvider("openai"); route.setEnabled(true);
            route.setBaseUrl("https://api.openai.com/v1");
            router.getModels().put("selected", route);
            var capability = factory.temperatureCapability("llmrouter.selected");
            assertEquals(name.equals("gpt-5") || name.equals("gpt-5-mini") ? ModelCapabilities.Support.NO
                    : ModelCapabilities.Support.UNKNOWN, capability.support());
            route.setBaseUrl("https://fixture.invalid/v1");
            assertEquals(ModelCapabilities.Support.UNKNOWN, factory.temperatureCapability("llmrouter.selected").support());
        }
        var local = new LlmRouterProperties.ModelConfig();
        local.setName("fixture-local:small"); local.setProvider("ollama"); local.setEnabled(true);
        local.setBaseUrl("http://127.0.0.1:11434/v1"); router.getModels().put("selected", local);
        assertEquals(ModelCapabilities.Support.YES, factory.temperatureCapability("llmrouter.selected").support());
        local.setEnabled(false);
        assertEquals(ModelCapabilities.Support.UNKNOWN, factory.temperatureCapability("llmrouter.selected").support());
        router.getAliases().put("alias", "chatgpt-oauth:gpt-5.5");
        assertEquals(ModelCapabilities.Support.NO, factory.temperatureCapability("alias").support());
        router.getAliases().put("a", "b");router.getAliases().put("b", "a");
        assertEquals(ModelCapabilities.Support.UNKNOWN, factory.temperatureCapability("a").support());
        verifyNoInteractions(keys);
    }
}
