package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.learning.gemini.GeminiGateway;
import com.example.lms.llm.spec.CloudModelCatalogLoader;
import com.example.lms.service.ChatModelCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GeminiSelectionReadinessContractTest {
    static Stream<Arguments> readinessCases() {
        return Stream.of(true, false).flatMap(probeEnabled -> Stream.of(
                Arguments.of(probeEnabled, false, true, "valid", "gemini_gateway_disabled"),
                Arguments.of(probeEnabled, true, false, "valid", "gemini_router_purpose_disabled"),
                Arguments.of(probeEnabled, true, true, "missing", "auth_missing"),
                Arguments.of(probeEnabled, true, true, "conflict", "credential_alias_conflict"),
                Arguments.of(probeEnabled, true, true, "valid", "")));
    }

    @ParameterizedTest @MethodSource("readinessCases")
    void pickerAndRouterBuilderAgreeWithoutNetworkEvenWhenProbeDisabled(boolean probeEnabled,
            boolean gatewayEnabled, boolean routerEnabled, String credentialCase, String expectedReason) {
        var env = new MockEnvironment().withProperty("gemini.gateway.enabled", String.valueOf(gatewayEnabled))
                .withProperty("gemini.gateway.purpose.router.enabled", String.valueOf(routerEnabled));
        if (!"missing".equals(credentialCase)) env.withProperty("GEMINI_API_KEY", "synthetic-gemini-fixture");
        if ("conflict".equals(credentialCase)) env.withProperty("gemini.api-key", "synthetic-conflict-fixture");
        var exchanges = new AtomicInteger();
        var gateway = new GeminiGateway(WebClient.builder().exchangeFunction(request -> {
            exchanges.incrementAndGet();
            return Mono.error(new AssertionError("readiness must never send"));
        }), new ProviderCredentialResolver(env), env);
        var props = new LlmGatewayProperties(); props.setEnabled(probeEnabled);
        var probe = new HybridLlmGatewayProbeService(props, null, null, new LlmRouteScorer(), env);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GeminiGateway.class, () -> gateway);
            context.refresh();
            context.getAutowireCapableBeanFactory().autowireBean(probe);
            var cfg = new LlmRouterProperties.ModelConfig();
            cfg.setEnabled(true); cfg.setProvider("gemini"); cfg.setName("gemini-3.8-flash");
            cfg.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai");
            var routes = new LlmRouterProperties(); routes.setModels(Map.of("gemini-pro", cfg));
            var loader = mock(CloudModelCatalogLoader.class);
            when(loader.loadDefaultCatalog()).thenReturn(List.of());
            var picker = new ChatModelCatalogService(new CloudModelRouteClassifier(loader, routes, probe),
                    null, new RestTemplateBuilder(), "https://fixture.invalid", false);
            ReflectionTestUtils.setField(picker, "remoteSelectionRoutes", "gemini-pro");
            var row = picker.resolve("llmrouter.gemini-pro").orElseThrow();
            boolean builderDisabled = gateway.buildOpenAiCompatibleChatModel(null)
                    .getClass().getSimpleName().equals("DisabledRouterChatModel");
            assertThat(row.selectable()).isEqualTo(!builderDisabled);
            assertThat(row.selectable()).isEqualTo(expectedReason.isEmpty());
            var json = new ObjectMapper().valueToTree(row);
            if (expectedReason.isEmpty()) {
                assertThat(row.status()).isEqualTo("configured");
                assertThat(json.path("reasons").toString()).isEqualTo("[]");
            } else {
                assertThat(json.path("reasons").toString()).contains(expectedReason);
            }
            assertThat(exchanges.get()).isZero();
        }
    }
}
