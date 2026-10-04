package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.llm.spec.CloudModelCatalogLoader;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CloudModelRouteClassifierRegisteredModelCatalogTest {
    @Test void verifiedGeminiManifestPreservesRegisteredPickerIdentityAndLegacyDisabledModel() {
        var snapshots = new CloudModelCatalogLoader(null).loadDefaultCatalog();
        assertThat(snapshots.stream().filter(s -> "gemini".equals(s.provider())).map(s -> s.model()))
                .contains("gemini-2.5-pro", "gemini-3.8-flash", "gemini-3.5-flash-lite");
        assertThat(snapshots.stream().filter(s -> "gemini-2.5-pro".equals(s.model())).findFirst().orElseThrow()
                .metadata().get("enabled")).isEqualTo(false);
        var cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(true); cfg.setName("gemini-3.8-flash"); cfg.setProvider("gemini");
        cfg.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai");
        var props = new LlmRouterProperties(); props.setModels(Map.of("gemini-pro", cfg));
        var gateway = mock(HybridLlmGatewayProbeService.class);
        when(gateway.evaluate("gemini-pro", cfg, "chat")).thenReturn(RoutingEligibility.eligible(
                "gemini-pro", "gemini", cfg.getName(), "chat", 100, true, Map.of()));
        var before = mock(CloudModelCatalogLoader.class);
        when(before.loadDefaultCatalog()).thenReturn(snapshots.stream()
                .filter(s -> !Set.of("gemini-3.8-flash", "gemini-3.5-flash-lite").contains(s.model())).toList());
        var after = mock(CloudModelCatalogLoader.class);
        when(after.loadDefaultCatalog()).thenReturn(snapshots);
        var oldChoice = picker(new CloudModelRouteClassifier(before, props, gateway));
        var newChoice = picker(new CloudModelRouteClassifier(after, props, gateway));
        assertThat(List.of(newChoice.id(), newChoice.endpointId(), newChoice.modelId(), newChoice.selectable()))
                .containsExactly(oldChoice.id(), oldChoice.endpointId(), oldChoice.modelId(), oldChoice.selectable());
        assertThat(newChoice.id()).isEqualTo("llmrouter.gemini-pro");
        assertThat(newChoice.endpointId()).isEqualTo("gemini-pro");
        assertThat(newChoice.selectable()).isTrue();
    }

    private static com.example.lms.service.ChatModelCatalogService.Choice picker(CloudModelRouteClassifier classifier) {
        var picker = new com.example.lms.service.ChatModelCatalogService(classifier, null,
                new org.springframework.boot.web.client.RestTemplateBuilder(), "https://fixture.invalid", false);
        org.springframework.test.util.ReflectionTestUtils.setField(picker, "remoteSelectionRoutes", "gemini-pro");
        return picker.resolve("llmrouter.gemini-pro").orElseThrow();
    }

    @Test void registeredChatRouteAbsentFromManifestIsProjectedWithoutModelNameGuessing() {
        var cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName("ambiguous:model"); cfg.setProvider("gemini");
        cfg.setBaseUrl("https://registered.invalid/v1"); cfg.setStage("chat");
        var props = new LlmRouterProperties(); props.setModels(Map.of("registered",cfg));
        var loader=mock(CloudModelCatalogLoader.class); when(loader.loadDefaultCatalog()).thenReturn(List.of());
        var gateway=mock(HybridLlmGatewayProbeService.class);
        when(gateway.evaluate("registered",cfg,"chat")).thenReturn(RoutingEligibility.eligible(
            "registered","gemini","ambiguous:model","chat",100,true,Map.of("capabilities",List.of("completion"))));
        var rows=new CloudModelRouteClassifier(loader,props,gateway).classifyDefaultCatalog("chat");
        assertThat(rows).hasSize(1); assertThat(rows.get(0).routeKey()).isEqualTo("registered");
        assertThat(rows.get(0).provider()).isEqualTo("gemini");
        assertThat(rows.get(0).capabilities()).containsExactly("completion");
        assertThat(rows.get(0).eligible()).isTrue();
    }
}
