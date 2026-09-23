package com.example.lms.llm;

import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.guard.KeyResolver;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicChatModelFactoryUsageMetadataTest {

    @Test
    void classifiesNativeAndOpenAiCompatibleCapsWithoutCountingModelConstruction() {
        DynamicChatModelFactory factory = configuredFactory();
        ChatUsageLedger ledger = new ChatUsageLedger();

        ChatModel nativeModel = factory.lcWithTimeout(
                "qwen3:8b", null, null, null, null, 64, 5);
        ChatModel compatModel = factory.lcWithTimeout(
                "gemma4:26b", null, null, null, null, 128, 5);

        ChatUsageLedger.ConfiguredCap nativeCap = DynamicChatModelFactory.configuredTokenBudget(nativeModel);
        ChatUsageLedger.ConfiguredCap compatCap = DynamicChatModelFactory.configuredTokenBudget(compatModel);
        assertEquals(ChatUsageLedger.CapState.EXPLICIT, nativeCap.state());
        assertEquals(ChatUsageLedger.ParameterKind.NUM_PREDICT, nativeCap.parameterKind());
        assertEquals(64, nativeCap.configuredCap());
        assertEquals(ChatUsageLedger.CapState.EXPLICIT, compatCap.state());
        assertEquals(ChatUsageLedger.ParameterKind.MAX_TOKENS, compatCap.parameterKind());
        assertEquals(128, compatCap.configuredCap());
        assertEquals(0L, ((Number) nested(ledger.snapshot(), "modelInvocations").get("attempts")).longValue());
    }

    @Test
    void nullCapIsReportedAsOmittedInsteadOfZero() {
        DynamicChatModelFactory factory = configuredFactory();

        ChatModel model = factory.lcWithTimeout(
                "qwen3:8b", null, null, null, null, null, 5);

        ChatUsageLedger.ConfiguredCap cap = DynamicChatModelFactory.configuredTokenBudget(model);
        assertEquals(ChatUsageLedger.CapState.OMITTED, cap.state());
        assertEquals(ChatUsageLedger.ParameterKind.OMITTED, cap.parameterKind());
        assertEquals(null, cap.configuredCap());
    }

    @Test
    void nonPositiveCapIsNotSentAndRemainsProviderDefaultUnknown() {
        DynamicChatModelFactory factory = configuredFactory();

        ChatModel model = factory.lcWithTimeout(
                "gemma4:26b", null, null, null, null, 0, 5);

        ChatUsageLedger.ConfiguredCap cap = DynamicChatModelFactory.configuredTokenBudget(model);
        assertEquals(ChatUsageLedger.CapState.PROVIDER_DEFAULT_UNKNOWN, cap.state());
        assertEquals(ChatUsageLedger.ParameterKind.OMITTED, cap.parameterKind());
        assertEquals(null, cap.configuredCap());
    }

    private static DynamicChatModelFactory configuredFactory() {
        MockEnvironment env = new MockEnvironment().withProperty("llm.api-key", "ollama");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(env, new KeyResolver(env));
        ReflectionTestUtils.setField(factory, "defaultModelName", "gemma4:26b");
        ReflectionTestUtils.setField(factory, "localBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "fastLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "highLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "judgeLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "visionLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "localApiKey", "ollama");
        ReflectionTestUtils.setField(factory, "ownerToken", "");
        ReflectionTestUtils.setField(factory, "allowedHosts", "");
        ReflectionTestUtils.setField(factory, "allowPrivateRemote", false);
        ReflectionTestUtils.setField(factory, "requireAuthForRemote", true);
        ReflectionTestUtils.setField(factory, "dynamicMaxRetries", 0);
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);
        return factory;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> nested(
            java.util.Map<String, Object> values,
            String key) {
        return (java.util.Map<String, Object>) values.get(key);
    }
}
