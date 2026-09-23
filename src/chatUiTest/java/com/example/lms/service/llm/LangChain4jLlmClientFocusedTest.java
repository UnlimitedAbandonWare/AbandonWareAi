package com.example.lms.service.llm;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LangChain4jLlmClientFocusedTest {

    @Test
    void webClientFiveHundredIsPromotedToLocalLlmOperatorAction() {
        TraceStore.clear();
        ChatModel failingModel = new OllamaNativeFailureModel();
        LangChain4jLlmClient client = new LangChain4jLlmClient(failingModel);

        assertEquals("", client.completeWithKey("disambiguation:clarify", "ping"));
        assertEquals(500, TraceStore.get("llm.client.httpStatus"));
        assertEquals("ollama_upstream_5xx", TraceStore.get("llm.client.upstreamFailureClass"));
        assertEquals("inspect_ollama_runtime_capacity", TraceStore.get("llm.client.upstreamNextAction"));
        assertEquals(500, TraceStore.get("llm.localSmoke.operatorAction.upstreamStatus"));
        assertEquals("ollama_upstream_5xx",
                TraceStore.get("llm.localSmoke.operatorAction.upstreamFailureClass"));
        assertEquals("inspect_ollama_runtime_capacity",
                TraceStore.get("llm.localSmoke.operatorAction.upstreamNextAction"));
        TraceStore.clear();
    }

    private static final class OllamaNativeFailureModel implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            throw WebClientResponseException.create(
                    500,
                    "Internal Server Error",
                    HttpHeaders.EMPTY,
                    "runtime capacity exhausted".getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        }
    }
}
