package com.example.lms.service.llm;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jLlmClient implements LlmClient {

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
