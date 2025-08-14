// src/main/java/com/example/lms/llm/OpenAiChatModel.java
package com.example.lms.llm;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OpenAiChatModel implements ChatModel {

    private final OpenAiService openAi;

    @Value("${openai.chat.model:gpt-4o-mini}")
    private String defaultModel;

    @Override
    public String generate(String prompt) {
        try {
            ChatCompletionRequest req = ChatCompletionRequest.builder()
                    .model(defaultModel)
                    .messages(List.of(new ChatMessage(ChatMessageRole.USER.value(), prompt)))
                    .temperature(0.2)
                    .topP(0.9)
                    .maxTokens(800)
                    .build();
            return openAi.createChatCompletion(req)
                    .getChoices()
                    .get(0)
                    .getMessage()
                    .getContent();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String generate(String prompt, double temperature, int maxTokens) {
        try {
            ChatCompletionRequest req = ChatCompletionRequest.builder()
                    .model(defaultModel)
                    .messages(List.of(new ChatMessage(ChatMessageRole.USER.value(), prompt)))
                    .temperature(temperature)
                    .topP(0.9)
                    .maxTokens(maxTokens)
                    .build();
            return openAi.createChatCompletion(req)
                    .getChoices()
                    .get(0)
                    .getMessage()
                    .getContent();
        } catch (Exception e) {
            return "";
        }
    }
}
