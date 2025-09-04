

        package com.example.lms.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/**
 * Configuration for lightweight ChatModels used throughout the application.
 *
 * <p>This version of the configuration unifies all LLM settings under the
 * {@code llm.*} namespace.  The mini and high models are configured from
 * the same base URL, API key and model name properties with sensible
 * defaults.  A fixed timeout is applied to avoid hanging calls.</p>
 */
@Configuration
public class LlmConfig {
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.lms.config.ModelGuard modelGuard;


    /**
     * Construct a lower capacity ("mini") {@link ChatModel} for tasks such as
     * rewriting, disambiguation and query transformation.  The model
     * definition is resolved via the {@code llm.chat-model} property which can
     * be overridden per environment.  When no value is provided the
     * application defaults to a small GPT‑4o variant.
     */
    @Bean
    @Qualifier("miniModel")
    public ChatModel miniModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.chat-model:gpt-5-mini}") String model
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelGuard.requireAllowedOrFallback(model))
                .temperature(0.2)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Construct a higher capacity {@link ChatModel} for tasks requiring more
     * reasoning capability.  The model name is read from
     * {@code llm.high.model} and falls back to {@code llm.chat-model} when
     * unspecified.  Temperature is inherited from {@code llm.chat.temperature}
     * and defaults to 0.2.
     */
    @Bean
    @Qualifier("highModel")
    public ChatModel highModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            // {스터프1} 변경 사항 적용: 기본 폴백 모델을 llama-3.1-8b-instant로 변경
            @Value("${llm.high.model:${llm.chat-model:llama-3.1-8b-instant}}") String model,
            @Value("${llm.chat.temperature:0.2}") double temperature
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelGuard.requireAllowedOrFallback(model))
                .temperature(temperature)
                .timeout(Duration.ofSeconds(30))
                .build();
    }
}