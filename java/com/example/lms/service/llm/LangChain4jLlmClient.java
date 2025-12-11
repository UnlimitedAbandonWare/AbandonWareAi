package com.example.lms.service.llm;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@RequiredArgsConstructor
public class LangChain4jLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmClient.class);

    private final ChatModel chatModel;

    @Override
    public String complete(String prompt) {
        try {
            var res = chatModel.chat(UserMessage.from(prompt));
            if (res == null || res.aiMessage() == null) return "";
            var ai = res.aiMessage();
            return ai.text() == null ? "" : ai.text();
        } catch (Exception e) {
            log.warn("LLM call failed: {}", e.toString());
            return "";
        }
    }
}