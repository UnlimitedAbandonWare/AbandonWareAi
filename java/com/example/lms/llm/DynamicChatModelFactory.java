package com.example.lms.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DynamicChatModelFactory {

    @Value("${openai.api.key:}")
    private String openaiApiKey;

    public ChatModel lc(String modelId, double temperature, double topP, Integer maxTokens) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .modelName(modelId)
                .temperature(temperature)
                .topP(topP);

        String key = StringUtils.hasText(openaiApiKey) ? openaiApiKey : System.getenv("OPENAI_API_KEY");
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("OpenAI API key is missing. Set 'openai.api.key' or ENV OPENAI_API_KEY.");
        }
        builder.apiKey(key);

        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        return builder.build();
    }

    /**  추천(Recommender) 작업용 보수적 세팅(temperature ≤ 0.2, topP=1.0) */
    public ChatModel lcWithPolicy(String intent,
                                  String modelId,
                                  double temperature,
                                  double topP,
                                  Integer maxTokens) {
        if ("RECOMMENDATION".equalsIgnoreCase(intent)) {
            temperature = Math.min(temperature, 0.2);
            topP = 1.0;
        }
        return lc(modelId, temperature, topP, maxTokens);
    }
}
