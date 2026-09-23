package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiEndpointCompatibilityTest {

    @Test
    void responsesPayloadOmitsUnsupportedSamplingParameters() {
        Map<String, Object> payload = OpenAiEndpointCompatibility.responsesPayload(
                "gpt-5.5-pro",
                "hello",
                512,
                0.2d);

        assertEquals("gpt-5.5-pro", payload.get("model"));
        assertEquals("hello", payload.get("input"));
        assertEquals(512, payload.get("max_output_tokens"));
        assertEquals(1.0d, payload.get("temperature"));
        assertFalse(payload.containsKey("top_p"));
        assertFalse(payload.containsKey("frequency_penalty"));
        assertFalse(payload.containsKey("presence_penalty"));
    }

    @Test
    void localModelClassifierRejectsRemoteChatModelsOnly() {
        assertTrue(ModelCapabilities.isRemoteLookingModelId("gpt-5.5-pro"));
        assertTrue(ModelCapabilities.isRemoteLookingModelId("o4-mini"));

        assertTrue(ModelCapabilities.isLocalChatModelId("gemma4:26b"));
        assertTrue(ModelCapabilities.isLocalChatModelId("qwen3:8b"));
        assertTrue(ModelCapabilities.isLocalChatModelId("qwen3:30b"));

        assertFalse(ModelCapabilities.isLocalChatModelId("gpt-5.5-pro"));
        assertFalse(ModelCapabilities.isLocalChatModelId("qwen3-embedding"));
    }

    @Test
    void summarizeForLogMasksSecretLikeEndpointBodies() {
        String raw = "{\"error\":\"failed api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + "\","
                + "\"authorization\":\"Bearer " + "raw-owner-token\"}";

        String summary = OpenAiEndpointCompatibility.summarizeForLog(raw, 420);

        assertFalse(summary.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(summary.contains("raw-owner-token"));
        assertTrue(summary.contains("redacted") || summary.contains("*"));
    }
}
