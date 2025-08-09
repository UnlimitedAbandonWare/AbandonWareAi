 package com.example.lms.service.llm;

 import dev.langchain4j.data.message.AiMessage;
 import dev.langchain4j.data.message.UserMessage;
 import dev.langchain4j.model.chat.ChatModel;
 import dev.langchain4j.model.chat.response.ChatResponse;
 import lombok.RequiredArgsConstructor;
 import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LangChain4jLlmClient implements LlmClient {

    private final ChatModel chatModel; // OpenAiChatModel 등 Bean 주입

    @Override
    public String complete(String prompt) {
        // LangChain4j 1.0.x: chat(...) -> ChatResponse
        ChatResponse res = chatModel.chat(UserMessage.from(prompt));
        if (res == null || res.aiMessage() == null) return "";
        AiMessage ai = res.aiMessage();
        return ai.text() == null ? "" : ai.text();
    }
}
