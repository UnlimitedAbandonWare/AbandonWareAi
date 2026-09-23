package com.example.lms.config;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OllamaNativeChatModel;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConfigLocalPlaceholderTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void operatorOffKeepsNonWebContextAndIndependentBeanAliveWithoutProviderConstruction() {
        LlmRouterProperties routes = new LlmRouterProperties();
        LlmRouterProperties.ModelConfig off = new LlmRouterProperties.ModelConfig();
        off.setEnabled(false);
        off.setName("qwen3:8b");
        off.setBaseUrl("http://127.0.0.1:19841/v1");
        off.setStage("chat");
        off.setDeviceRole("rtx3090");
        LlmRouterProperties.ModelConfig independent = new LlmRouterProperties.ModelConfig();
        independent.setEnabled(true);
        independent.setName("qwen3:8b");
        independent.setBaseUrl("http://127.0.0.1:19842/v1");
        independent.setStage("chat");
        independent.setDeviceRole("rtx3060");
        routes.setModels(Map.of("off", off, "independent", independent));

        new ApplicationContextRunner()
                .withUserConfiguration(LlmConfig.class)
                .withBean(LlmRouterProperties.class, () -> routes)
                .withBean(KeyResolver.class, () -> new KeyResolver(
                        new MockEnvironment().withProperty("llm.api-key", "ollama")))
                .withBean(ModelRuntimeHealthTracker.class, ModelRuntimeHealthTracker::new)
                .withPropertyValues("llm.api-key=ollama", "llm.chat-model=qwen3:8b",
                        "llm.base-url=http://127.0.0.1:19841/v1",
                        "llm.fast.base-url=http://127.0.0.1:19842/v1", "llm.fast.model=qwen3:8b",
                        "llm.explore.base-url=http://127.0.0.1:19841/v1",
                        "llm.mini.model=qwen3:8b", "llm.explore.model=qwen3:8b",
                        "llm.judge.model=qwen3:8b", "llm.high.model=qwen3:8b")
                .run(context -> {
                    org.junit.jupiter.api.Assertions.assertNull(context.getStartupFailure());
                    assertFalse(context.getBean("fastChatModel") instanceof ExpectedFailureChatModel);
                    org.junit.jupiter.api.Assertions.assertSame(
                            context.getBean("fastChatModel"), context.getBean("greenChatModel"));
                    for (String name : List.of("chatModel", "miniModel", "exploreChatModel", "judgeChatModel", "highModel")) {
                        ChatModel model = org.junit.jupiter.api.Assertions.assertInstanceOf(
                                ExpectedFailureChatModel.class, context.getBean(name), name);
                        var failure = assertThrows(com.example.lms.llm.gateway.LlmGatewayException.class,
                                () -> model.chat(List.of(UserMessage.from("synthetic OFF bean probe"))), name);
                        assertEquals("route_disabled", failure.reasonCode());
                        assertEquals(com.example.lms.llm.gateway.LlmFailureClass.DISABLED, failure.failureClass());
                    }
                    org.junit.jupiter.api.Assertions.assertSame(
                            context.getBean("chatModel"), context.getBean("redChatModel"));
                });
    }

    @Test
    void loopbackOllamaPlaceholderBuildsChatModels() {
        LlmConfig config = config();
        KeyResolver resolver = new KeyResolver(new MockEnvironment()
                .withProperty("llm.api-key", "ollama"));

        ChatModel chat = config.chatModel(
                "http://localhost:11434/v1",
                resolver,
                ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL,
                0.3d,
                12L,
                0);
        ChatModel fast = config.fastChatModel(
                "http://localhost:11434/v1",
                resolver,
                "qwen3:8b",
                0.0d,
                5L,
                0,
                256);

        assertFalse(chat instanceof ExpectedFailureChatModel);
        assertFalse(fast instanceof ExpectedFailureChatModel);
        assertTrue(fast instanceof OllamaNativeChatModel);
        assertEquals("local", TraceStore.get("llm.primary.provider"));
        assertEquals(11434, TraceStore.get("llm.primary.port"));
        assertEquals("local", TraceStore.get("llm.fast.provider"));
        assertEquals(11434, TraceStore.get("llm.fast.port"));
        assertFalse(TraceStore.getAll().containsKey("llm.fast.roleAwareLoopbackOverride"));
        assertFalse(TraceStore.getAll().containsValue("http://localhost:11434/v1"));
    }

    @Test
    void loopbackOllamaOnNonDefaultPortBuildsNativeFastModel() {
        LlmConfig config = config();
        KeyResolver resolver = new KeyResolver(new MockEnvironment()
                .withProperty("llm.api-key", "ollama"));

        ChatModel fast = config.fastChatModel(
                "http://127.0.0.1:11436/v1",
                resolver,
                "qwen3:8b",
                0.0d,
                5L,
                0,
                256);

        assertTrue(fast instanceof OllamaNativeChatModel);
        assertEquals(11436, TraceStore.get("llm.fast.port"));
    }

    @Test
    void remoteLocalGatewayRejectsOllamaPlaceholderWithoutOwnerToken() {
        LlmConfig config = config();
        ReflectionTestUtils.setField(config, "allowPrivateRemote", true);
        ReflectionTestUtils.setField(config, "allowedHosts", "macmini-ollama.internal");
        KeyResolver resolver = new KeyResolver(new MockEnvironment()
                .withProperty("llm.api-key", "ollama"));

        assertThrows(IllegalStateException.class, () -> config.chatModel(
                "https://macmini-ollama.internal/v1",
                resolver,
                ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL,
                0.3d,
                12L,
                0));
    }

    @Test
    void missingKeyWarningDoesNotWriteRawModelIdentifier() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/LlmConfig.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("bean={} model={}"));
        assertTrue(source.contains("bean={} modelHash={} modelLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(model)"));
    }

    @Test
    void maxTokenCompatibilityLogDoesNotWriteRawModelIdentifier() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/LlmConfig.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("model='{}' rejects max_tokens; skipping"));
        assertTrue(source.contains("modelHash={} modelLength={} rejects max_tokens; skipping"));
        assertTrue(source.contains("SafeRedactor.hashValue(model), lengthOf(model)"));
    }

    @Test
    void highModelHonorsConfiguredMaxTokensWithCompatibilityGuard() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/LlmConfig.java"), StandardCharsets.UTF_8);
        int highStart = source.indexOf("public ChatModel highModel(");
        int highEnd = source.indexOf("public NightmareBreaker nightmareBreaker", highStart);
        assertTrue(highStart >= 0);
        assertTrue(highEnd > highStart);
        String highMethod = source.substring(highStart, highEnd);

        assertTrue(highMethod.contains("@Value(\"${llm.high.max-tokens:1024}\") Integer maxTokens"));
        assertTrue(highMethod.contains("OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)"));
        assertTrue(highMethod.contains("builder.maxTokens(maxTokens);"));
        assertTrue(highMethod.indexOf("applyGatewayHeaders(builder, sanitizedBaseUrl, model);")
                < highMethod.indexOf("builder.maxTokens(maxTokens);"));
    }

    @Test
    void applicationLlmYamlDefinesHighMaxTokensDefault() throws Exception {
        String yaml = Files.readString(Path.of("main/resources/application-llm.yaml"), StandardCharsets.UTF_8);
        int highStart = yaml.indexOf("  high:");
        int highEnd = yaml.indexOf("  explore:", highStart);
        String highBlock = yaml.substring(highStart, highEnd);

        assertTrue(highBlock.contains("    max-tokens: ${LLM_HIGH_MAX_TOKENS:1024}"));
    }

    @Test
    void annotatedSpringChatModelFactoryKeepsConcreteDisabledModelAndWritesOneNoOutboundRow() {
        LlmConfig config = config();
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("spring-disabled-request", "spring-disabled-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "spring-disabled-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        KeyResolver missingKeyResolver = new KeyResolver(new MockEnvironment().withProperty("llm.api-key", ""));

        ChatModel result = config.chatModel(
                "http://127.0.0.1:11434/v1",
                missingKeyResolver,
                "spring-disabled-model",
                0.3d,
                12L,
                64,
                0,
                tracker);

        ExpectedFailureChatModel disabled = (ExpectedFailureChatModel) result;
        disabled.chat(List.of(UserMessage.from("spring-disabled-fixture")));

        List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, attempts.size());
        Map<String, Object> attempt = attempts.get(0);
        assertEquals("disabled", attempt.get("failureClass"));
        assertFalse((Boolean) attempt.get("modelAdapterAttemptObserved"));
        assertFalse((Boolean) attempt.get("clientHttpExchangeObserved"));
        assertFalse((Boolean) attempt.get("clientHttpResponseObserved"));
        assertFalse((Boolean) attempt.get("providerAttemptObserved"));
        assertFalse((Boolean) attempt.get("wireAttemptObserved"));
        assertEquals("hash:unknown", attempt.get("responseHash"));
        assertEquals(0, attempt.get("responseCharCount"));
        assertEquals(0, attempt.get("responseUtf8ByteCount"));
        assertFalse((Boolean) attempt.get("responseObserved"));
        assertEquals(13, attempt.get("optionItemCount"));
    }

    @Test
    void everyActiveLlmConfigNoKeyBeanFamilyUsesTheSharedDisabledEvidenceHook() {
        LlmConfig config = config();
        ModelRuntimeHealthTracker tracker = pendingTimeline("all-beans-disabled", "all-beans-session");
        KeyResolver missing = new KeyResolver(new MockEnvironment().withProperty("llm.api-key", ""));

        List<ChatModel> disabled = List.of(
                config.miniModel("http://127.0.0.1:11434/v1", missing, "mini-disabled", 0.2d, 12L, 0, tracker),
                config.exploreChatModel("http://127.0.0.1:11434/v1", missing, "explore-disabled", 0.8d, 6L, 0, 512, tracker),
                config.judgeChatModel("http://127.0.0.1:11434/v1", missing, "judge-disabled", 6L, 0, 512, tracker),
                config.highModel("http://127.0.0.1:11434/v1", missing, "high-disabled", 0.3d, 30L, 0, 1024, tracker));

        disabled.forEach(model -> {
            assertTrue(model instanceof ExpectedFailureChatModel);
            model.chat(List.of(UserMessage.from("disabled bean probe")));
        });

        List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(
                String.valueOf(TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY)));
        assertEquals(4, rows.size());
        rows.forEach(LlmConfigLocalPlaceholderTest::assertDisabledUnobserved);
    }

    @Test
    void conditionalUtilityChatModelNoKeyPathUsesSharedDisabledEvidenceHook() {
        ModelRuntimeHealthTracker tracker = pendingTimeline("utility-disabled", "utility-session");
        LangChainConfig config = new LangChainConfig(null, null);
        ReflectionTestUtils.setField(config, "openAiKey", "sk-local");
        ReflectionTestUtils.setField(config, "chatModelName", "utility-disabled-model");
        ReflectionTestUtils.setField(config, "chatTemperature", 0.7d);
        ReflectionTestUtils.setField(config, "openAiTimeoutSec", 60L);

        ChatModel disabled = config.utilityChatModel(true, tracker);
        disabled.chat(List.of(UserMessage.from("utility disabled probe")));

        List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(
                String.valueOf(TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY)));
        assertEquals(1, rows.size());
        assertDisabledUnobserved(rows.get(0));
    }

    private static ModelRuntimeHealthTracker pendingTimeline(String requestId, String sessionId) {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline(requestId, sessionId);
        tracker.recordRequestPhase(timelineId, "dispatch", "disabled-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        return tracker;
    }

    private static void assertDisabledUnobserved(Map<String, Object> row) {
        assertEquals("failed", row.get("outcome"));
        assertEquals("disabled", row.get("failureClass"));
        assertEquals("configuration_error", row.get("terminalClass"));
        assertEquals("hash:unknown", row.get("responseHash"));
        assertEquals(0, row.get("responseCharCount"));
        assertEquals(0, row.get("responseUtf8ByteCount"));
        assertFalse((Boolean) row.get("responseObserved"));
        assertFalse((Boolean) row.get("modelAdapterAttemptObserved"));
        assertFalse((Boolean) row.get("clientHttpExchangeObserved"));
        assertFalse((Boolean) row.get("clientHttpResponseObserved"));
        assertFalse((Boolean) row.get("providerAttemptObserved"));
        assertFalse((Boolean) row.get("wireAttemptObserved"));
    }

    @Test
    void applicationLlmYamlFastRouteFallsBackToPrimaryUnlessFastEndpointIsExplicit() throws Exception {
        String yaml = Files.readString(Path.of("main/resources/application-llm.yaml"), StandardCharsets.UTF_8);
        int fastStart = yaml.indexOf("  fast:");
        int fastEnd = yaml.indexOf("  high:", fastStart);
        String fastBlock = yaml.substring(fastStart, fastEnd);

        assertTrue(fastBlock.contains(
                "    base-url: ${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}"));
        assertFalse(fastBlock.contains("http://127.0.0.1:11435/v1"));

        String rootYaml = Files.readString(Path.of("main/resources/application.yml"), StandardCharsets.UTF_8);
        int rootLlmStart = rootYaml.indexOf("llm:");
        int rootFastStart = rootYaml.indexOf("  fast:", rootLlmStart);
        int rootFastEnd = rootYaml.indexOf("  # ---------------------------------------------------------------------------", rootFastStart);
        String rootFastBlock = rootYaml.substring(rootFastStart, rootFastEnd);

        assertTrue(rootFastBlock.contains(
                "    base-url: ${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}"));
        assertFalse(rootFastBlock.contains("http://127.0.0.1:11435/v1"));
    }

    private static LlmConfig config() {
        LlmConfig config = new LlmConfig();
        ReflectionTestUtils.setField(config, "ownerToken", "");
        ReflectionTestUtils.setField(config, "ownerTokenHeader", "X-Owner-Token");
        ReflectionTestUtils.setField(config, "allowPrivateRemote", false);
        ReflectionTestUtils.setField(config, "allowedHosts", "");
        ReflectionTestUtils.setField(config, "requireAuthForRemote", true);
        return config;
    }
}
