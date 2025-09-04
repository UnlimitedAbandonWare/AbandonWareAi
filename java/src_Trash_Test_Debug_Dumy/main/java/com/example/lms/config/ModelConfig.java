package com.example.lms.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * Configuration for instantiating different ChatModel beans.
 *
 * <p>
 * This configuration defines three separate beans:
 * a primary default model and two named models (mini and high). The default
 * model corresponds to the baseline model defined by {@code openai.api.model.default},
 * while the mini and high models map to the router configuration values
 * {@code router.moe.mini} and {@code router.moe.high} respectively.
 * These beans allow downstream components, particularly the {@code ModelRouter},
 * to select an appropriate model based on routing heuristics without hardcoding
 * any model names in service logic.
 * </p>
 */
@Configuration
public class ModelConfig {
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.lms.config.ModelGuard modelGuard;


    /**
     * Primary ChatModel bean used when no specific qualifier is provided. This
     * picks up the default model name from the {@code openai.api.model.default}
     * property, falling back to {@code gpt-5-mini} if unspecified. Base URL
     * and API key are also resolved from the environment but retain literal
     * placeholders if not set.
     */
    @Bean
    @Primary
    public ChatModel defaultChatModel(Environment env) {
        String provider = env.getProperty("llm.provider", "openai");
        String baseUrl, apiKey, modelName;
        if ("groq".equalsIgnoreCase(provider)) {
            baseUrl = env.getProperty("llm.base-url",
                    env.getProperty("groq.base-url", "https://api.groq.com/openai/v1"));
            apiKey = firstNonBlank(env.getProperty("groq.api.key"),
                                   env.getProperty("GROQ_API_KEY"),
                                   env.getProperty("llm.api-key"),
                                   env.getProperty("LLM_API_KEY"));
            modelName = firstNonBlank(env.getProperty("groq.default-model"),
                                      "llama-3.1-8b-instant");
        } else {
            baseUrl = env.getProperty("openai.api.url", "https://api.openai.com/v1");
            apiKey = firstNonBlank(env.getProperty("openai.api.key"),
                                   env.getProperty("OPENAI_API_KEY"),
                                   env.getProperty("llm.api-key"),
                                   env.getProperty("LLM_API_KEY"));
            modelName = firstNonBlank(env.getProperty("openai.api.model.default"),
                                      env.getProperty("openai.default-model"),
                                      env.getProperty("llm.default-model"),
                                      "gpt-5-mini");
        }
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey == null ? "" : apiKey)
                .modelName(modelGuard.requireAllowedOrFallback(modelName, provider))
                .timeout(java.time.Duration.ofSeconds(30)).build();
    }

    /**
     * Mini tier ChatModel used for cost‑efficient requests. The model name is
     * derived from {@code router.moe.mini} and defaults to {@code gpt-5-mini}
     * when the property is absent.
     */
    @Bean(name = "mini")
    public ChatModel miniChatModel(
            @Value("${router.moe.mini:gpt-5-mini}") String modelName,
            Environment env) {
        String baseUrl = env.getProperty("openai.api.url", "https://api.openai.com/v1");
        String apiKey = java.util.Optional.ofNullable(env.getProperty("openai.api.key")).filter(s->!s.isBlank()).orElse(env.getProperty("llm.api-key",""));
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelGuard.requireAllowedOrFallback(modelName))
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * High tier ChatModel used for high‑quality requests. The model name is
     * resolved from {@code router.moe.high} and defaults to
     * {@code gpt-5-chat-latest} if unspecified.
     */
    @Bean(name = "high")
    public ChatModel highChatModel(@Value("${router.moe.high:gpt-5-chat-latest}") String requested,
                                   Environment env) {
        String provider = env.getProperty("llm.provider", "openai");
        String baseUrl = "groq".equalsIgnoreCase(provider)
                ? env.getProperty("llm.base-url",
                    env.getProperty("groq.base-url", "https://api.groq.com/openai/v1"))
                : env.getProperty("openai.api.url", "https://api.openai.com/v1");
        String apiKey = "groq".equalsIgnoreCase(provider)
                ? firstNonBlank(env.getProperty("groq.api.key"), env.getProperty("GROQ_API_KEY"),
                                env.getProperty("llm.api-key"), env.getProperty("LLM_API_KEY"))
                : firstNonBlank(env.getProperty("openai.api.key"), env.getProperty("OPENAI_API_KEY"),
                                env.getProperty("llm.api-key"), env.getProperty("LLM_API_KEY"));
        String modelName = modelGuard.requireAllowedOrFallback(requested, provider);
        return OpenAiChatModel.builder().baseUrl(baseUrl).apiKey(apiKey == null ? "" : apiKey)
                .modelName(modelName).timeout(java.time.Duration.ofSeconds(30)).build();
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
