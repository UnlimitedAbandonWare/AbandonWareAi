package com.example.lms.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;



/**
 * LangChain4j 1.0.1 전용 ChatModel 빈 구성.
 * - defaultChatModel: @Primary, 일반 주입 대상
 * - highModel: @Qualifier("highModel"), 고급/검증 경로용
 *
 * baseUrl을 Groq로 바꾸면 Groq OpenAI-compatible 엔드포인트로 동작합니다.
 */
/**
 * Legacy LangChain4j bean definitions.
 *
 * <p>This project previously defined {@link dev.langchain4j.model.chat.ChatModel} beans in
 * multiple configuration classes and relied on {@code spring.main.allow-bean-definition-overriding=true}.
 * That makes runtime behaviour unpredictable (which model/timeout/retry wins depends on scan order).
 *
 * <p>As part of the orchestration hardening work, the canonical ChatModel beans now live in
 * {@link com.example.lms.config.LlmConfig}. This legacy config is disabled by default.
 */
@Configuration
@ConditionalOnProperty(name = "legacy.langchain4j-beans.enabled", havingValue = "true")
public class LangChain4jBeans {

    @Bean
    @Primary
public ChatModel defaultChatModel(
            @Value("${llm.base-url:${llm.base-url}}") String baseUrl,
            @Value("${llm.api-key:${OPENAI_API_KEY:${GROQ_API_KEY:}}}") String apiKey,
            @Value("${llm.chat-model}") String model,
            @Value("${llm.chat.temperature:0.2}") double temperature
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .build();
    }

    @Bean
    @Qualifier("highModel")
    public ChatModel highModel(
            @Value("${llm.base-url:${llm.base-url}}") String baseUrl,
            @Value("${llm.api-key:${OPENAI_API_KEY:${GROQ_API_KEY:}}}") String apiKey,
            // 별도 설정 없으면 기본 모델을 재사용
            @Value("${llm.high.model:${llm.chat-model:${openai.api.model:gemma3:27b}}}") String model,
            @Value("${llm.high.temperature:${llm.chat.temperature:0.2}}") double temperature
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .build();
    }


@Bean
@Qualifier("localChatModel")
    public dev.langchain4j.model.chat.ChatModel localChatModel(
        @Value("${llm.base-url}") String baseUrl,
        @Value("${llm.api-key}") String apiKey,
        @Value("${llm.chat-model}") String model
) {
    return dev.langchain4j.model.openai.OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .build();
}

}
