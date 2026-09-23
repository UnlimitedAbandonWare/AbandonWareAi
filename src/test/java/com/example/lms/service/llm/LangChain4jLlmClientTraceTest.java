package com.example.lms.service.llm;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jLlmClientTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void tracesStageOutputAndModelClassWithoutPromptLeak() {
        LangChain4jLlmClient client = new LangChain4jLlmClient(new StaticModel("OK"));

        String result = client.completeWithKey("disambiguation:clarify", "private prompt must stay out of trace");

        assertEquals("OK", result);
        assertEquals("disambiguation:clarify", TraceStore.get("llm.client.stage"));
        assertEquals("StaticModel", TraceStore.get("llm.client.modelClass"));
        assertEquals(2, ((Number) TraceStore.get("llm.client.outputLength")).intValue());
        assertEquals(Boolean.FALSE, TraceStore.get("llm.client.blank"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private prompt"), trace);
    }

    @Test
    void blankResponseIsTracedAsBlankWithoutPromptLeak() {
        LangChain4jLlmClient client = new LangChain4jLlmClient(new StaticModel("   "));

        String result = client.completeWithKey("disambiguation:clarify", "secret query body must stay out");

        assertEquals("   ", result);
        assertEquals("disambiguation:clarify", TraceStore.get("llm.client.stage"));
        assertEquals(3, ((Number) TraceStore.get("llm.client.outputLength")).intValue());
        assertEquals(Boolean.TRUE, TraceStore.get("llm.client.blank"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("secret query body"), trace);
    }

    @Test
    void modelExceptionIsTracedWithoutRawPromptOrMessageLeak() {
        LangChain4jLlmClient client = new LangChain4jLlmClient(
                new ThrowingModel(new RuntimeException("private provider message")));

        String result = client.completeWithKey("disambiguation:clarify", "private prompt must stay out");

        assertEquals("", result);
        assertEquals("disambiguation:clarify", TraceStore.get("llm.client.stage"));
        assertEquals("ThrowingModel", TraceStore.get("llm.client.modelClass"));
        assertEquals(Boolean.TRUE, TraceStore.get("llm.client.failed"));
        assertEquals("RuntimeException", TraceStore.get("llm.client.errorType"));
        assertTrue(String.valueOf(TraceStore.get("llm.client.errorHash")).startsWith("hash:"));
        assertEquals(24, ((Number) TraceStore.get("llm.client.errorLength")).intValue());

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private prompt"), trace);
        assertFalse(trace.contains("private provider message"), trace);
    }

    private record StaticModel(String text) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }

    private record ThrowingModel(RuntimeException failure) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            throw failure;
        }
    }
}
