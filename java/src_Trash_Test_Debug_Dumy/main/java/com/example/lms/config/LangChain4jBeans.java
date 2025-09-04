package com.example.lms.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * LangChain4j 1.0.1 전용 ChatModel 빈 구성.
 * - defaultChatModel: @Primary, 일반 주입 대상
 * - highModel: @Qualifier("highModel"), 고급/검증 경로용
 *
 * baseUrl을 Groq로 바꾸면 Groq OpenAI-compatible 엔드포인트로 동작합니다.
 */
@Configuration
public class LangChain4jBeans {
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.lms.config.ModelGuard modelGuard;


    @Bean
    @Primary
    public ChatModel defaultChatModel(
            @Value("${llm.base-url:https://api.openai.com/v1}") String baseUrl,
            // Inject the LLM API key strictly from configuration.  No environment fallback
            // (e.g. OPENAI_API_KEY or GROQ_API_KEY) is permitted.
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.chat-model:${openai.api.model:gpt-5-mini}}") String model,
            @Value("${llm.chat.temperature:0.2}") double temperature
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelGuard.requireAllowedOrFallback(model)) // modelGuard를 통해 안전한 모델 ID 사용
                .timeout(java.time.Duration.ofSeconds(60))
                .temperature(temperature)
                .build();
    }

    @Bean
    @Qualifier("highModel")
    public ChatModel highModel(
            @Value("${llm.base-url:https://api.openai.com/v1}") String baseUrl,
            // Inject the LLM API key strictly from configuration.  No environment fallback
            // (e.g. OPENAI_API_KEY or GROQ_API_KEY) is permitted.
            @Value("${llm.api-key}") String apiKey,
            // 별도 설정 없으면 기본 모델을 재사용
            @Value("${llm.high.model:${llm.chat-model:${openai.api.model:gpt-5-mini}}}") String model,
            @Value("${llm.high.temperature:${llm.chat.temperature:0.2}}") double temperature
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelGuard.requireAllowedOrFallback(model)) // modelGuard를 통해 안전한 모델 ID 사용
                .timeout(java.time.Duration.ofSeconds(60))
                .temperature(temperature)
                .build();
    }
}
