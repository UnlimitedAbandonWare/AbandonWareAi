package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCapabilitiesTest {

    @Test
    void defaultLocalChatModelUsesInstalledGemma4RoleDefault() {
        assertEquals("gemma4:26b", ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL);
        assertEquals("qwen3.5:9b", ModelCapabilities.DEFAULT_LOCAL_FAST_MODEL);
        assertEquals("smtek/Qwen3.8-27B:Q3_K_XL", ModelCapabilities.DEFAULT_LOCAL_JUDGE_MODEL);
        assertEquals("smtek/Qwen3.8-27B:Q3_K_XL", ModelCapabilities.DEFAULT_LOCAL_CODER_MODEL);
        assertEquals("qwen3-vl:8b", ModelCapabilities.DEFAULT_LOCAL_VISION_MODEL);
        assertEquals("qwen3-embedding:4b", ModelCapabilities.DEFAULT_LOCAL_EMBEDDING_MODEL);
    }

    @Test
    void canonicalModelNamePreservesGemma4QuantizedTagCase() {
        assertEquals("gemma4:31b-it-q4_K_M",
                ModelCapabilities.canonicalModelName("gemma4:31b-it-q4_K_M"));
    }

    @Test
    void canonicalModelNameStripsLangChainPrefixCaseInsensitively() {
        assertEquals("gemma4:31b-it-q4_K_M",
                ModelCapabilities.canonicalModelName("LC:gemma4:31b-it-q4_K_M"));
    }

    @Test
    void canonicalModelNameStripsOnlyTrailingOrchestrationTags() {
        assertEquals("gemma4:31b-it-q4_K_M",
                ModelCapabilities.canonicalModelName("lc:gemma4:31b-it-q4_K_M:fallback:evidence"));
    }

    @Test
    void placeholderModelIdsAreNotLocalChatModels() {
        assertFalse(ModelCapabilities.isLocalChatModelId("model"));
        assertFalse(ModelCapabilities.isLocalChatModelId("unknown"));
        assertFalse(ModelCapabilities.isLocalChatModelId("${LLM_CHAT_MODEL}"));
        assertTrue(ModelCapabilities.isLocalChatModelId("gemma4:26b"));
    }

    @Test
    void localAliasNormalizerDoesNotRewriteRealInstalledModelIds() {
        assertEquals("gemma4:26b", ModelCapabilities.normalizeLocalModelAlias("gemma4:26b"));
        assertEquals("gemma4:26b", ModelCapabilities.normalizeLocalModelAlias("gemma4-26b"));
        assertEquals("gemma3:27b", ModelCapabilities.normalizeLocalModelAlias("gemma3:27b"));
        assertEquals("gemma3:4b", ModelCapabilities.normalizeLocalModelAlias("gemma3:4b"));
        assertEquals(ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL,
                ModelCapabilities.normalizeLocalModelAlias(null));
    }

    @Test
    void localAliasNormalizerDoesNotCollapseNonGemmaAliases() {
        assertEquals("qwen3:8b", ModelCapabilities.normalizeLocalModelAlias("qwen3:8b"));
        assertEquals("qwen2.5:7b-instruct", ModelCapabilities.normalizeLocalModelAlias("qwen2.5:7b-instruct"));
        assertEquals("llama-3.1-8b-instruct", ModelCapabilities.normalizeLocalModelAlias("llama-3.1-8b-instruct"));
        assertEquals("gpt-5-chat-latest", ModelCapabilities.normalizeLocalModelAlias("gpt-5-chat-latest"));
    }
}
