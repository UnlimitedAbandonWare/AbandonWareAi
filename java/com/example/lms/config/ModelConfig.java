package com.example.lms.config;

import dev.langchain4j.model.openai.OpenAiChatModel;

import dev.langchain4j.model.chat.ChatModel;   // ✅ 1.0.1

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelConfig {

    // application.properties 의 openai.api.key 우선, 없으면 환경변수 OPENAI_API_KEY 사용
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String apiKey;
    // Centralised model names are provided via openai.chat.model.default and openai.chat.model.moe.
    // Avoid hard‑coding fallback values here; defaults are defined in application.properties.
    @Value("${openai.chat.model.default:${openai.model.moe:${langchain4j.openai.chat-model.model-name:gpt-4o-mini}}}")
    private String miniModelName;
    @Value("${openai.chat.model.moe:${openai.model.moe:${langchain4j.openai.chat-model.model-name:gpt-4o-mini}}}")
    private String moeModel;
    @Value("${openai.chat.model.moe}")
    private String highModelName;

    @Value("${local-llm.enabled:false}")
    private boolean localEnabled;

    @Value("${local-llm.base-url:}")
    private String localBaseUrl;

// import org.springframework.util.StringUtils;

    @Bean
    @Qualifier("mini")
    public ChatModel miniModel() {
        var b = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(miniModelName)
                .timeout(java.time.Duration.ofSeconds(65));

        if (localEnabled && org.springframework.util.StringUtils.hasText(localBaseUrl)) {
            b = b.baseUrl(localBaseUrl.trim());
        }

        return b.build();
    }

    @Bean
    @Qualifier("high")
    public ChatModel highModel() {
        var b = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(highModelName)
                .timeout(java.time.Duration.ofSeconds(65));
        if (localEnabled && org.springframework.util.StringUtils.hasText(localBaseUrl)) {
            b = b.baseUrl(localBaseUrl.trim());
        }
        return b.build();
    }
}
