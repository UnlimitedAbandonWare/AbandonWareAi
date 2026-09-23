package com.example.lms.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModelMetaSupportTest {

    @Test
    void resolveModelUsedPrefersConcreteModelAndPreservesFallbackSuffix() {
        assertEquals("gpt-4.1-mini", ChatModelMetaSupport.resolveModelUsed(
                "gpt-4.1-mini",
                "local-model",
                "fallback-model"));

        assertEquals("requested-model:fallback:evidence", ChatModelMetaSupport.resolveModelUsed(
                "OpenAiChatModel:fallback:evidence",
                "requested-model",
                "fallback-model"));

        assertEquals("fallback-model", ChatModelMetaSupport.resolveModelUsed(
                "OpenAiChatModel",
                null,
                "fallback-model"));
    }

    @Test
    void extractsPersistedModelAndTraceMetaPrefixes() {
        assertEquals("model-a", ChatModelMetaSupport.extractModelUsed("?MODEL?model-a"));
        assertEquals("model-b", ChatModelMetaSupport.extractModelUsed("[MODEL] model-b"));
        assertEquals("<section>trace</section>", ChatModelMetaSupport.extractTraceHtml("?TRACE?<section>trace</section>"));
        assertNull(ChatModelMetaSupport.extractModelUsed("normal answer"));
    }

    @Test
    void wrapperLabelsAreRecognizedWithoutRejectingConcreteIds() {
        assertTrue(ChatModelMetaSupport.isWrapperLabel("lc:OpenAiChatModel:fallback:evidence"));
        assertTrue(ChatModelMetaSupport.isWrapperLabel("OpenAiChatModel"));
        assertEquals("real-chat-model", ChatModelMetaSupport.resolveModelUsed(
                "real-chat-model",
                "requested",
                "fallback-model"));
    }
}
