package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.llm.spec.CloudModelCatalogLoader;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CloudModelRouteClassifierRegisteredModelCatalogTest {
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
