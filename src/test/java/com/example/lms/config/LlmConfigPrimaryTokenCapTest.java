package com.example.lms.config;

import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.OllamaNativeChatModel;
import com.example.lms.llm.OpenAiModelParamMatrix;
import com.example.lms.llm.OpenAiTokenParamCompat;
import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmConfigPrimaryTokenCapTest {
    private OpenAiModelParamMatrix previousMatrix;

    @BeforeEach
    void preserveMatrix() {
        previousMatrix = (OpenAiModelParamMatrix) ReflectionTestUtils.getField(OpenAiTokenParamCompat.class, "MATRIX");
        OpenAiTokenParamCompat.registerMatrix(null);
    }

    @AfterEach
    void restoreContext() {
        OpenAiTokenParamCompat.registerMatrix(previousMatrix);
        TraceStore.clear();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 512, 4096})
    void primaryCompatibleModelRetainsConfiguredLegacyCap(int cap) {
        OpenAiChatModel model = assertInstanceOf(OpenAiChatModel.class, primary("gemma4:26b", cap));
        assertEquals(cap, model.defaultRequestParameters().maxOutputTokens());
        assertNull(model.defaultRequestParameters().maxCompletionTokens());
        assertEquals("gemma4:26b", model.defaultRequestParameters().modelName());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 512})
    void primaryCompatibleModelUsesCompletionCapWhenTheExistingMatrixSelectsIt(int cap) {
        matrixTokenKey("max_completion_tokens");
        OpenAiChatModel model = assertInstanceOf(OpenAiChatModel.class, primary("gemma4:26b", cap));
        assertEquals(cap, model.defaultRequestParameters().maxCompletionTokens());
        assertNull(model.defaultRequestParameters().maxOutputTokens());
    }

    @Test
    void explicitMatrixOmissionDoesNotIntroduceATokenParameter() {
        matrixTokenKey(null);
        OpenAiChatModel model = assertInstanceOf(OpenAiChatModel.class, primary("gemma4:26b", 64));
        assertNull(model.defaultRequestParameters().maxOutputTokens());
        assertNull(model.defaultRequestParameters().maxCompletionTokens());
    }

    @Test
    void absentCapKeepsProviderDefaultUnspecified() {
        OpenAiChatModel model = assertInstanceOf(OpenAiChatModel.class, primary("gemma4:26b", null));
        assertNull(model.defaultRequestParameters().maxOutputTokens());
        assertNull(model.defaultRequestParameters().maxCompletionTokens());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveCapKeepsExistingGenericOmission(int cap) {
        OpenAiChatModel model = assertInstanceOf(OpenAiChatModel.class, primary("gemma4:26b", cap));
        assertNull(model.defaultRequestParameters().maxOutputTokens());
        assertNull(model.defaultRequestParameters().maxCompletionTokens());
    }

    @Test
    void nativePrimaryRouteStillRetainsItsConfiguredCap() {
        OllamaNativeChatModel model = assertInstanceOf(OllamaNativeChatModel.class, primary("qwen3:8b", 64));
        assertEquals(64, ReflectionTestUtils.getField(model, "maxTokens"));
    }

    private static ChatModel primary(String model, Integer cap) {
        LlmConfig config = new LlmConfig();
        ReflectionTestUtils.setField(config, "ownerToken", "");
        ReflectionTestUtils.setField(config, "ownerTokenHeader", "X-Owner-Token");
        ReflectionTestUtils.setField(config, "allowPrivateRemote", false);
        ReflectionTestUtils.setField(config, "allowedHosts", "");
        ReflectionTestUtils.setField(config, "requireAuthForRemote", true);
        KeyResolver keys = new KeyResolver(new MockEnvironment().withProperty("llm.api-key", "ollama"));
        // Construction only: never invoke chat or connect to the loopback endpoint.
        return config.chatModel("http://127.0.0.1:11434/v1", keys, model, 0.3d, 5L, cap, 0,
                new com.example.lms.llm.ModelRuntimeHealthTracker());
    }

    private static void matrixTokenKey(String key) {
        OpenAiModelParamMatrix matrix = mock(OpenAiModelParamMatrix.class);
        when(matrix.tokenParamKey(anyString(), anyString())).thenReturn(key);
        OpenAiTokenParamCompat.registerMatrix(matrix);
    }
}
