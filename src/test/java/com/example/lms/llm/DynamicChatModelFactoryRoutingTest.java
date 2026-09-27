package com.example.lms.llm;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicChatModelFactoryRoutingTest {

    @ParameterizedTest
    @ValueSource(strings = {"qwen3:8b", "legacy-off", "llmrouter.disabled"})
    void operatorOffStopsPlainAliasAndSelfCallBeforeConstruction(String requested) {
        LlmRouterProperties routes = new LlmRouterProperties();
        routes.setModels(Map.of("disabled", localRoute(false, "http://127.0.0.1:19841", "chat", "rtx3090")));
        routes.setAliases(Map.of("legacy-off", "llmrouter.disabled"));
        routeContext(routes, "http://127.0.0.1:19841/v1").run(context -> {
            DynamicChatModelFactory factory = context.getBean(DynamicChatModelFactory.class);
            LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                    () -> factory.lc(requested, null, null, null, null, 32));
            assertEquals("route_disabled", failure.reasonCode());
            assertEquals(LlmFailureClass.DISABLED, failure.failureClass());
        });
    }

    @Test
    void operatorOffWithConflictingDeviceMappingHoldsOnlyTheMatchingRequest() {
        LlmRouterProperties routes = new LlmRouterProperties();
        routes.setModels(Map.of(
                "primary", localRoute(false, "http://127.0.0.1:19841/v1", "chat", "rtx3090"),
                "secondary", localRoute(true, "http://127.0.0.1:19841/v1", "chat", "rtx3060")));
        routeContext(routes, "http://127.0.0.1:19841/v1").run(context -> {
            DynamicChatModelFactory factory = context.getBean(DynamicChatModelFactory.class);
            LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                    () -> factory.lcWithTimeout("qwen3:8b", null, null, null, null, 32, 1));
            assertEquals("route_mapping_unconfirmed", failure.reasonCode());
            assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(failure));
            assertNotNull(factory.lcWithTimeout("gemma3:4b", null, null, null, null, 32, 1));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-role", "missing-stage", "different-stage"})
    void operatorOffNeverInfersMissingOrConflictingMapping(String mode) {
        LlmRouterProperties routes = new LlmRouterProperties();
        LlmRouterProperties.ModelConfig off = localRoute(false, "http://127.0.0.1:19841/v1",
                mode.equals("missing-stage") ? null : "chat", mode.equals("missing-role") ? null : "rtx3090");
        routes.setModels(mode.equals("different-stage") ? Map.of("disabled", off,
                "judge", localRoute(false, "http://127.0.0.1:19841/v1", "judge", "rtx3090")) : Map.of("disabled", off));
        routeContext(routes, "http://127.0.0.1:19841/v1").run(context -> {
            DynamicChatModelFactory factory = context.getBean(DynamicChatModelFactory.class);
            LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                    () -> factory.lcWithTimeout("qwen3:8b", null, null, null, null, 32, 1));
            assertEquals("route_mapping_unconfirmed", failure.reasonCode());
            assertEquals("route_disabled", assertThrows(LlmGatewayException.class,
                    () -> factory.lc("llmrouter.disabled", null, null, null, null, 32)).reasonCode());
        });
    }

    @Test
    void operatorOffDoesNotDisableAnIndependentEnabledEndpoint() {
        LlmRouterProperties routes = new LlmRouterProperties();
        routes.setModels(Map.of(
                "primary", localRoute(false, "http://127.0.0.1:19841/v1", "chat", "rtx3090"),
                "secondary", localRoute(true, "http://127.0.0.1:19842/v1", "chat", "rtx3060")));
        routeContext(routes, "http://127.0.0.1:19842/v1").run(context ->
                assertNotNull(context.getBean(DynamicChatModelFactory.class)
                        .lcWithTimeout("qwen3:8b", null, null, null, null, 32, 1)));
    }

    private static LlmRouterProperties.ModelConfig localRoute(
            boolean enabled, String endpoint, String stage, String deviceRole) {
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(enabled);
        cfg.setName("qwen3:8b");
        cfg.setBaseUrl(endpoint);
        cfg.setProvider("local");
        cfg.setStage(stage);
        cfg.setDeviceRole(deviceRole);
        return cfg;
    }

    private static ApplicationContextRunner routeContext(LlmRouterProperties routes, String endpoint) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DynamicChatModelFactory.class)
                .withBean(LlmRouterProperties.class, () -> routes)
                .withBean(KeyResolver.class, () -> new KeyResolver(new MockEnvironment()
                        .withProperty("llm.api-key", "ollama")
                        .withProperty("OPENAI_API_KEY", "loopback-key-value")))
                .withBean(ModelRuntimeHealthTracker.class, ModelRuntimeHealthTracker::new)
                .withBean(LlmGatewayProperties.class, LlmGatewayProperties::new)
                .withPropertyValues("llm.chat-model=qwen3:8b", "llm.api-key=ollama",
                        "llm.base-url=" + endpoint, "llm.fast.base-url=" + endpoint,
                        "llm.high.base-url=" + endpoint, "llm.judge.base-url=" + endpoint,
                        "llm.coder.base-url=" + endpoint, "llm.vision.base-url=" + endpoint,
                        "llm.base-url-openai=" + endpoint);
    }

    @Test
    void fastQwenPreservesConfiguredPrimaryLoopbackWhenNoFastEndpointIsAvailable() {
        DynamicChatModelFactory factory = factory(
                "http://localhost:11434/v1",
                "http://localhost:11434/v1");

        String route = ReflectionTestUtils.invokeMethod(factory, "selectLocalBaseUrl", "qwen3:8b");

        assertEquals("http://localhost:11434/v1", route);
    }

    @Test
    void explicitFastQwenRouteIsPreserved() {
        DynamicChatModelFactory factory = factory(
                "http://localhost:11434/v1",
                "http://127.0.0.1:11435/v1");

        String route = ReflectionTestUtils.invokeMethod(factory, "selectLocalBaseUrl", "qwen3:8b");

        assertEquals("http://127.0.0.1:11435/v1", route);
    }

    @Test
    void qwen35NineBUsesConfiguredFastEndpoint() {
        DynamicChatModelFactory factory = factory(
                "http://localhost:11434/v1",
                "http://127.0.0.1:11435/v1");

        String route = ReflectionTestUtils.invokeMethod(factory, "selectLocalBaseUrl", "qwen3.5:9b");

        assertEquals("http://127.0.0.1:11435/v1", route);
    }

    @Test
    void gemma4ModelsUseAssignedGpuEndpoints() {
        DynamicChatModelFactory factory = factory(
                "http://localhost:11436/v1",
                "http://127.0.0.1:11435/v1",
                "http://127.0.0.1:11434/v1");

        String twelveBRoute = ReflectionTestUtils.invokeMethod(factory, "selectLocalBaseUrl", "gemma4:12b");
        String thirtyOneBRoute = ReflectionTestUtils.invokeMethod(factory, "selectLocalBaseUrl", "gemma4:31b");

        assertEquals("http://127.0.0.1:11435/v1", twelveBRoute);
        assertEquals("http://127.0.0.1:11434/v1", thirtyOneBRoute);
    }

    @Test
    void onlyExactGemma4TwelveBOnLoopbackUsesNativeThinkFalseAdapter() {
        DynamicChatModelFactory factory = factory(
                "http://127.0.0.1:11434/v1",
                "http://127.0.0.1:11435/v1");
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);

        Boolean twelveBUsesNative = ReflectionTestUtils.invokeMethod(
                factory,
                "shouldUseOllamaNativeThinkFalse",
                "gemma4:12b",
                "http://127.0.0.1:11435/v1");
        Boolean thirtyOneBUsesNative = ReflectionTestUtils.invokeMethod(
                factory,
                "shouldUseOllamaNativeThinkFalse",
                "gemma4:31b",
                "http://127.0.0.1:11434/v1");
        Boolean qualifiedTwelveBUsesNative = ReflectionTestUtils.invokeMethod(
                factory,
                "shouldUseOllamaNativeThinkFalse",
                "gemma4:12b-extra",
                "http://127.0.0.1:11435/v1");
        Boolean remoteTwelveBUsesNative = ReflectionTestUtils.invokeMethod(
                factory,
                "shouldUseOllamaNativeThinkFalse",
                "gemma4:12b",
                "https://gpu.example/v1");
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", false);
        Boolean disabledTwelveBUsesNative = ReflectionTestUtils.invokeMethod(
                factory,
                "shouldUseOllamaNativeThinkFalse",
                "gemma4:12b",
                "http://127.0.0.1:11435/v1");

        assertEquals(Boolean.TRUE, twelveBUsesNative);
        assertEquals(Boolean.FALSE, thirtyOneBUsesNative);
        assertEquals(Boolean.FALSE, qualifiedTwelveBUsesNative);
        assertEquals(Boolean.FALSE, remoteTwelveBUsesNative);
        assertEquals(Boolean.FALSE, disabledTwelveBUsesNative);
    }

    @Test
    void fastGemmaPreservesPrimaryWhenNoFastEndpointIsAvailable() {
        DynamicChatModelFactory factory = factory(
                "http://localhost:11434/v1",
                "http://localhost:11434/v1",
                "http://localhost:11436/v1");

        String route = ReflectionTestUtils.invokeMethod(factory, "selectLocalBaseUrl", "gemma3:4b");

        assertEquals("http://localhost:11434/v1", route);
    }

    @Test
    void explicitFastGemmaRouteIsPreserved() {
        DynamicChatModelFactory factory = factory(
                "http://localhost:11434/v1",
                "http://127.0.0.1:11435/v1",
                "http://localhost:11436/v1");

        String route = ReflectionTestUtils.invokeMethod(factory, "selectLocalBaseUrl", "gemma3:4b");

        assertEquals("http://127.0.0.1:11435/v1", route);
    }

    @Test
    void qwenOnArbitraryLoopbackPortUsesNativeThinkFalseAdapter() {
        DynamicChatModelFactory factory = factory(
                "http://127.0.0.1:11436/v1",
                "http://127.0.0.1:11436/v1");
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);

        Boolean useNative = ReflectionTestUtils.invokeMethod(
                factory,
                "shouldUseOllamaNativeThinkFalse",
                "qwen3:8b",
                "http://127.0.0.1:11436/v1");

        assertEquals(Boolean.TRUE, useNative);
    }

    @Test
    void qwen35OnLoopbackUsesNativeThinkFalseAdapter() {
        DynamicChatModelFactory factory = factory(
                "http://127.0.0.1:11435/v1",
                "http://127.0.0.1:11435/v1");
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);

        Boolean useNative = ReflectionTestUtils.invokeMethod(
                factory,
                "shouldUseOllamaNativeThinkFalse",
                "qwen3.5:9b",
                "http://127.0.0.1:11435/v1");
        Boolean unverifiedTagUsesNative = ReflectionTestUtils.invokeMethod(
                factory,
                "shouldUseOllamaNativeThinkFalse",
                "qwen3.5:other",
                "http://127.0.0.1:11435/v1");

        assertEquals(Boolean.TRUE, useNative);
        assertEquals(Boolean.FALSE, unverifiedTagUsesNative);
    }

    @Test
    void conflictingLocalCredentialAliasesFailClosedBeforeModelConstruction() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.api-key", "local-factory-secret-a")
                .withProperty("LLM_API_KEY", "local-factory-secret-b");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(env, new KeyResolver(env));
        ReflectionTestUtils.setField(factory, "defaultModelName", "gemma3:4b");
        ReflectionTestUtils.setField(factory, "localBaseUrl", "http://localhost:11434/v1");
        ReflectionTestUtils.setField(factory, "fastLocalBaseUrl", "http://localhost:11434/v1");
        ReflectionTestUtils.setField(factory, "highLocalBaseUrl", "http://localhost:11434/v1");
        ReflectionTestUtils.setField(factory, "judgeLocalBaseUrl", "http://localhost:11434/v1");
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", "http://localhost:11434/v1");
        ReflectionTestUtils.setField(factory, "visionLocalBaseUrl", "http://localhost:11434/v1");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory.lcWithTimeout("gemma3:4b", null, null, null, null, 32, 1));

        assertTrue(failure.getMessage().contains("conflicting-credential-aliases"));
    }

    private static DynamicChatModelFactory factory(String localBaseUrl, String fastLocalBaseUrl) {
        return factory(localBaseUrl, fastLocalBaseUrl, localBaseUrl);
    }

    private static DynamicChatModelFactory factory(
            String localBaseUrl,
            String fastLocalBaseUrl,
            String highLocalBaseUrl
    ) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.api-key", "ollama");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(env, new KeyResolver(env));
        ReflectionTestUtils.setField(factory, "localBaseUrl", localBaseUrl);
        ReflectionTestUtils.setField(factory, "fastLocalBaseUrl", fastLocalBaseUrl);
        ReflectionTestUtils.setField(factory, "highLocalBaseUrl", highLocalBaseUrl);
        ReflectionTestUtils.setField(factory, "judgeLocalBaseUrl", localBaseUrl);
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", localBaseUrl);
        ReflectionTestUtils.setField(factory, "visionLocalBaseUrl", fastLocalBaseUrl);
        return factory;
    }
}
