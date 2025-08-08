// src/main/java/com/example/lms/service/verification/FactStatusClassifier.java
package com.example.lms.service.verification;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component("factStatusClassifier") // 명확하게 식별 가능
@RequiredArgsConstructor
public class FactStatusClassifier {

    private final OpenAiService openAi;

    private static final String CLS_TEMPLATE = """
        You are a classification model.
        Read the Question / Context / Draft and answer with exactly one token:
        PASS, CORRECTED or INSUFFICIENT.

        ## QUESTION
        %s

        ## CONTEXT
        %s

        ## DRAFT
        %s
        """;

    public FactVerificationStatus classify(String q, String ctx, String draft, String model) {

        String prompt = String.format(CLS_TEMPLATE, q, ctx, draft);

        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(new ChatMessage(ChatMessageRole.SYSTEM.value(), prompt)))
                .temperature(0d)
                .topP(0d)
                .maxTokens(1)
                .build();

        String raw = openAi.createChatCompletion(req)
                .getChoices().get(0).getMessage().getContent()
                .trim().toUpperCase();

        return switch (raw) {
            case "PASS"          -> FactVerificationStatus.PASS;
            case "CORRECTED"     -> FactVerificationStatus.CORRECTED;
            case "INSUFFICIENT" -> FactVerificationStatus.INSUFFICIENT;
            default             -> FactVerificationStatus.UNKNOWN;
        };
    }
}
