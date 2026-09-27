package com.example.lms.llm;

import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamicChatModelFactoryMatrixOmissionTest {
    private static final String MODEL = "gemma4:26b";
    private static final String BASE = "http://127.0.0.1:11434/v1";
    private OpenAiModelParamMatrix previousMatrix;
    private RecordingTracker tracker;
    private DynamicChatModelFactory factory;

    @BeforeEach
    void prepareConstructionOnlyFixture() {
        previousMatrix = (OpenAiModelParamMatrix) ReflectionTestUtils.getField(OpenAiTokenParamCompat.class, "MATRIX");
        OpenAiTokenParamCompat.registerMatrix(null);
        tracker = new RecordingTracker();
        MockEnvironment env = new MockEnvironment().withProperty("llm.api-key", "ollama");
        LlmGatewayProperties gateway = new LlmGatewayProperties();
        gateway.getLocalDeviceFailover().setEnabled(false);
        factory = new DynamicChatModelFactory(env, new KeyResolver(env), tracker, gateway);
        ReflectionTestUtils.setField(factory, "defaultModelName", MODEL);
        for (String field : new String[]{"localBaseUrl", "fastLocalBaseUrl", "highLocalBaseUrl",
                "judgeLocalBaseUrl", "coderLocalBaseUrl", "visionLocalBaseUrl"}) {
            ReflectionTestUtils.setField(factory, field, BASE);
        }
        ReflectionTestUtils.setField(factory, "localApiKey", "ollama");
        ReflectionTestUtils.setField(factory, "ownerToken", "");
        ReflectionTestUtils.setField(factory, "allowedHosts", "");
        ReflectionTestUtils.setField(factory, "allowPrivateRemote", false);
        ReflectionTestUtils.setField(factory, "requireAuthForRemote", true);
        ReflectionTestUtils.setField(factory, "dynamicMaxRetries", 0);
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);
    }

    @AfterEach
    void restoreContext() {
        OpenAiTokenParamCompat.registerMatrix(previousMatrix);
        TraceStore.clear();
    }

    @ParameterizedTest
    @CsvSource({"model,omit", "prefix,none", "base,disabled", "default,omit"})
    void explicitOmissionAgreesAcrossRealModelLedgerAndAttemptEnvelope(String scope, String rule) {
        registerRule(scope, rule);
        assertNull(OpenAiTokenParamCompat.tokenParamKey(MODEL, BASE));
        ChatModel created = create(MODEL, 64);
        OpenAiChatModel delegate = assertInstanceOf(OpenAiChatModel.class, tracker.delegate);
        ChatUsageLedger.ConfiguredCap cap = DynamicChatModelFactory.configuredTokenBudget(created);
        assertAll(
                () -> assertNull(delegate.defaultRequestParameters().maxOutputTokens()),
                () -> assertNull(delegate.defaultRequestParameters().maxCompletionTokens()),
                () -> assertEquals(64, cap.normalizedRequestCap()),
                () -> assertNull(cap.configuredCap()),
                () -> assertEquals(ChatUsageLedger.CapState.OMITTED, cap.state()),
                () -> assertEquals(ChatUsageLedger.ParameterKind.OMITTED, cap.parameterKind()),
                () -> assertEquals(ChatUsageLedger.CapSource.NORMALIZED_REQUEST, cap.source()),
                () -> assertEquals("unknown", tracker.options.get("maxTokens")),
                () -> assertEquals("unknown", tracker.options.get("maxOutputTokens")));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 512})
    void explicitLegacyRuleRetainsAllThreeCapRepresentations(int value) {
        registerRule("model", "max_tokens");
        assertExplicit(value, ChatUsageLedger.ParameterKind.MAX_TOKENS, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 512})
    void explicitCompletionRuleRetainsAllThreeCapRepresentations(int value) {
        registerRule("model", "max_completion_tokens");
        assertExplicit(value, ChatUsageLedger.ParameterKind.MAX_COMPLETION_TOKENS, true);
    }

    @Test
    void absentMatrixRetainsLegacyDefault() {
        assertExplicit(64, ChatUsageLedger.ParameterKind.MAX_TOKENS, false);
    }

    @Test
    void absentRequestCapRemainsOmittedUnderAnOmitRule() {
        registerRule("model", "omit");
        ChatModel created = create(MODEL, null);
        assertNoConfiguredCap(created, ChatUsageLedger.CapState.OMITTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveRequestRemainsProviderDefaultUnknownUnderAnOmitRule(int value) {
        registerRule("model", "omit");
        ChatModel created = create(MODEL, value);
        assertNoConfiguredCap(created, ChatUsageLedger.CapState.PROVIDER_DEFAULT_UNKNOWN);
    }

    @Test
    void nativeRouteKeepsNumPredictDespiteAnOpenAiMatrixOmitRule() {
        registerRule("default", "omit");
        ChatModel created = create("qwen3:8b", 64);
        OllamaNativeChatModel delegate = assertInstanceOf(OllamaNativeChatModel.class, tracker.delegate);
        ChatUsageLedger.ConfiguredCap cap = DynamicChatModelFactory.configuredTokenBudget(created);
        assertEquals(64, ReflectionTestUtils.getField(delegate, "maxTokens"));
        assertEquals(ChatUsageLedger.CapState.EXPLICIT, cap.state());
        assertEquals(ChatUsageLedger.ParameterKind.NUM_PREDICT, cap.parameterKind());
        assertEquals(64, cap.configuredCap());
        assertEquals(64, tracker.options.get("maxOutputTokens"));
        assertEquals("unknown", tracker.options.get("maxTokens"));
    }

    private void assertExplicit(int value, ChatUsageLedger.ParameterKind kind, boolean completion) {
        ChatModel created = create(MODEL, value);
        OpenAiChatModel delegate = assertInstanceOf(OpenAiChatModel.class, tracker.delegate);
        assertEquals(completion ? null : value, delegate.defaultRequestParameters().maxOutputTokens());
        assertEquals(completion ? value : null, delegate.defaultRequestParameters().maxCompletionTokens());
        assertEquals(MODEL, delegate.defaultRequestParameters().modelName());
        ChatUsageLedger.ConfiguredCap cap = DynamicChatModelFactory.configuredTokenBudget(created);
        assertEquals(ChatUsageLedger.CapState.EXPLICIT, cap.state());
        assertEquals(kind, cap.parameterKind());
        assertEquals(value, cap.normalizedRequestCap());
        assertEquals(value, cap.configuredCap());
        assertEquals(value, tracker.options.get(completion ? "maxOutputTokens" : "maxTokens"));
        assertEquals("unknown", tracker.options.get(completion ? "maxTokens" : "maxOutputTokens"));
    }

    private void assertNoConfiguredCap(ChatModel created, ChatUsageLedger.CapState state) {
        OpenAiChatModel delegate = assertInstanceOf(OpenAiChatModel.class, tracker.delegate);
        assertNull(delegate.defaultRequestParameters().maxOutputTokens());
        assertNull(delegate.defaultRequestParameters().maxCompletionTokens());
        ChatUsageLedger.ConfiguredCap cap = DynamicChatModelFactory.configuredTokenBudget(created);
        assertEquals(state, cap.state());
        assertEquals(ChatUsageLedger.ParameterKind.OMITTED, cap.parameterKind());
        assertNull(cap.configuredCap());
        assertNull(cap.normalizedRequestCap());
        assertEquals("unknown", tracker.options.get("maxTokens"));
        assertEquals("unknown", tracker.options.get("maxOutputTokens"));
    }

    private ChatModel create(String model, Integer cap) {
        // Build real models and the real attempt wrapper. Never invoke chat or connect.
        ChatModel created = factory.lcWithTimeout(model, null, null, null, null, cap, 5);
        assertEquals(1, tracker.decorations);
        assertNotSame(tracker.delegate, created);
        return created;
    }

    private static void registerRule(String scope, String value) {
        OpenAiModelParamMatrix matrix = new OpenAiModelParamMatrix();
        switch (scope) {
            case "model" -> matrix.setByModel(Map.of(MODEL, value));
            case "prefix" -> matrix.setByPrefix(Map.of("gemma4:", value));
            case "base" -> {
                OpenAiModelParamMatrix.RuleSet rule = new OpenAiModelParamMatrix.RuleSet();
                rule.setTokenParamDefault(value);
                matrix.setByBaseUrl(Map.of("127.0.0.1", rule));
            }
            case "default" -> matrix.setTokenParamDefault(value);
            default -> throw new IllegalArgumentException("unknown fixture scope");
        }
        matrix.registerCompatBridge();
    }

    private static class RecordingTracker extends ModelRuntimeHealthTracker {
        private ChatModel delegate;
        private Map<String, ?> options;
        private int decorations;

        @Override
        public ChatModel decorateRequestAttempt(ChatModel model, String role,
                                                RequestAttemptRoute route, Map<String, ?> envelope) {
            delegate = model;
            options = new LinkedHashMap<>(envelope);
            decorations++;
            return super.decorateRequestAttempt(model, role, route, envelope);
        }
    }
}
