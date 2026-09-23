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
 * LangChain4j 1.0.1 ?袁⑹뒠 ChatModel ???닌딄쉐.
 * - defaultChatModel: @Primary, ??곗뺘 雅뚯눘?????? * - highModel: @Qualifier("highModel"), ?⑥쥒??野꺜筌?野껋럥以?? *
 * baseUrl??Groq嚥?獄쏅떽?筌?Groq OpenAI-compatible ?遺얜굡????紐껋쨮 ??덉삂??몃빍??
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
            @Value("${llm.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${llm.api-key:${LLM_API_KEY:${OPENAI_API_KEY:${GROQ_API_KEY:ollama}}}}") String apiKey,
            @Value("${llm.chat-model:${llm.model:gemma4:26b}}") String model,
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
            @Value("${llm.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${llm.api-key:${LLM_API_KEY:${OPENAI_API_KEY:${GROQ_API_KEY:ollama}}}}") String apiKey,
            // Use high model override when configured; otherwise follow local chat default.
            @Value("${llm.high.model:${llm.chat-model:${openai.api.model:gemma4:26b}}}") String model,
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
        @Value("${llm.base-url:http://localhost:11434/v1}") String baseUrl,
        @Value("${llm.api-key:${LLM_API_KEY:${OPENAI_API_KEY:${GROQ_API_KEY:ollama}}}}") String apiKey,
        @Value("${llm.chat-model:${llm.model:gemma4:26b}}") String model
) {
    return dev.langchain4j.model.openai.OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .build();
}

}
