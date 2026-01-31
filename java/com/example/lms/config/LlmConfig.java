package com.example.lms.config;

import com.example.lms.llm.OpenAiTokenParamCompat;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.OpenAiCompatBaseUrl;

import com.example.lms.guard.KeyResolver;
import com.example.lms.guard.ModelGuard;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareBreakerProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LLM configuration.
 *
 * <p>Important:
 * - Keep internal retries low (ideally 0 for fast/utility models) so the orchestration
 *   layer (timeouts/circuit breakers) can make consistent decisions.
 * - Some LangChain4j versions expose {@code maxRetries(Integer)} instead of
 *   {@code maxRetries(int)}. We therefore apply it via a dual-signature reflection helper.
 * </p>
 */
@Configuration
@EnableConfigurationProperties(NightmareBreakerProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);
    private static final AtomicBoolean MAX_RETRIES_WARNED = new AtomicBoolean(false);

    private static void applyMaxRetries(Object builder, int maxRetries) {
        if (builder == null) return;

        Method m = null;

        // 1) public method lookup
        try {
            m = builder.getClass().getMethod("maxRetries", Integer.class);
        } catch (NoSuchMethodException ignore) {
        }
        if (m == null) {
            try {
                m = builder.getClass().getMethod("maxRetries", int.class);
            } catch (NoSuchMethodException ignore) {
            }
        }

        // 2) declared method fallback (walk superclass chain)
        if (m == null) {
            m = findDeclaredMethod(builder.getClass(), "maxRetries", Integer.class);
        }
        if (m == null) {
            m = findDeclaredMethod(builder.getClass(), "maxRetries", int.class);
        }

        if (m == null) {
            warnOnce("maxRetries method not found on builder: {}", builder.getClass().getName());
            return;
        }

        try {
            m.setAccessible(true);
            Class<?> p0 = m.getParameterTypes()[0];
            if (p0 == Integer.class) {
                m.invoke(builder, Integer.valueOf(maxRetries));
            } else {
                m.invoke(builder, maxRetries);
            }
        } catch (Exception e) {
            warnOnce("Failed to apply maxRetries={}: {}", maxRetries, e.getMessage());
        }
    }

    private static Method findDeclaredMethod(Class<?> type, String name, Class<?> param) {
        Class<?> c = type;
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            try {
                return c.getDeclaredMethod(name, param);
            } catch (NoSuchMethodException ignore) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static void warnOnce(String fmt, Object... args) {
        if (MAX_RETRIES_WARNED.compareAndSet(false, true)) {
            log.warn("[LlmConfig] " + fmt, args);
        }
    }



    @Bean(name = {"chatModel","redChatModel"})
    @Primary
    public ChatModel chatModel(
            @Value("${llm.base-url}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.chat-model}") String model,
            @Value("${llm.chat.temperature:0.3}") double temperature,
            @Value("${llm.timeout-seconds:12}") long timeoutSeconds,
            // NOTE: keep internal retries fail-fast by default (0).
            // Outer orchestrators / caller-level retry should own the policy to avoid stacked timeouts.
            @Value("${llm.max-retries:0}") int maxRetries
    ) {
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);

        var builder = OpenAiChatModel.builder()
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        applyMaxRetries(builder, maxRetries);

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

    @Bean(name = "miniModel")
    public ChatModel miniModel(
            @Value("${llm.base-url}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.mini.model:${llm.chat-model}}") String model,
            @Value("${llm.mini.temperature:0.2}") double temperature,
            @Value("${llm.mini.timeout-seconds:12}") long timeoutSeconds,
            // NOTE: keep internal retries fail-fast by default (0).
            @Value("${llm.mini.max-retries:0}") int maxRetries
    ) {
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);

        var builder = OpenAiChatModel.builder()
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        applyMaxRetries(builder, maxRetries);

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

    /**
     * Fast model for utility tasks (QueryTransformer, disambiguation, etc.).
     *
     * <p>Hard rule: avoid internal retries for the fast model. Upper orchestration already
     * applies time budgets / fallbacks, and internal retries tend to create zombie work
     * under cancellation/timeouts.</p>
     */
    @Bean(name = {"fastChatModel","greenChatModel"})
    public ChatModel fastChatModel(
            @Value("${llm.fast.base-url:${llm.base-url}}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.fast.model:${llm.chat-model}}") String model,
            @Value("${llm.fast.temperature:0.0}") double temperature,
            @Value("${llm.fast.timeout-seconds:5}") long timeoutSeconds,
            @Value("${llm.fast.max-retries:0}") int maxRetries,
            @Value("${llm.fast.max-tokens:256}") Integer maxTokens
    ) {
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);

        var builder = OpenAiChatModel.builder()
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        if (maxTokens != null) {
            if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
                builder.maxTokens(maxTokens);
            } else {
                log.info("[OpenAI-Compat] model='{}' rejects max_tokens; skipping", model);
            }
        }

        applyMaxRetries(builder, maxRetries);

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

@Bean(name = "exploreChatModel")
public ChatModel exploreChatModel(
        @Value("${llm.explore.base-url:${llm.base-url}}") String baseUrl,
        KeyResolver keyResolver,
        @Value("${llm.explore.model:${llm.chat-model}}") String model,
        @Value("${llm.explore.temperature:0.85}") double temperature,
        @Value("${llm.explore.timeout-seconds:6}") long timeoutSeconds,
        @Value("${llm.explore.max-retries:0}") int maxRetries,
        @Value("${llm.explore.max-tokens:512}") Integer maxTokens
) {
    String apiKey = keyResolver.resolveLocalApiKeyStrict();
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

    String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);

    var builder = OpenAiChatModel.builder()
            .baseUrl(sanitizedBaseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
            .timeout(Duration.ofSeconds(timeoutSeconds));
    if (maxTokens != null) {
            if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
            builder.maxTokens(maxTokens);
        } else {
            log.info("[OpenAI-Compat] model='{}' rejects max_tokens; skipping", model);
        }
    }

    applyMaxRetries(builder, maxRetries);

    // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
    builder.modelName(model);
    return builder.build();
}

@Bean(name = "judgeChatModel")
public ChatModel judgeChatModel(
        @Value("${llm.judge.base-url:${llm.base-url}}") String baseUrl,
        KeyResolver keyResolver,
        @Value("${llm.judge.model:${llm.high.model:${llm.chat-model}}}") String model,
        @Value("${llm.judge.timeout-seconds:6}") long timeoutSeconds,
        @Value("${llm.judge.max-retries:0}") int maxRetries,
        @Value("${llm.judge.max-tokens:512}") Integer maxTokens
) {
    String apiKey = keyResolver.resolveLocalApiKeyStrict();
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

    String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);

    // Deterministic judge: keep temperature at 0 (or the model's fixed default for rigid sampling models).
    var builder = OpenAiChatModel.builder()
            .baseUrl(sanitizedBaseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .temperature(ModelCapabilities.sanitizeTemperature(model, 0.0d))
            .timeout(Duration.ofSeconds(timeoutSeconds));
    if (maxTokens != null) {
        if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
            builder.maxTokens(maxTokens);
        } else {
            log.info("[OpenAI-Compat] model='{}' rejects max_tokens; skipping", model);
        }
    }

    applyMaxRetries(builder, maxRetries);

    // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
    builder.modelName(model);
    return builder.build();
}

    @Bean(name = "highModel")
    public ChatModel highModel(
            @Value("${llm.base-url}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.high.model:${llm.chat-model}}") String model,
            @Value("${llm.high.temperature:0.3}") double temperature,
            @Value("${llm.high.timeout-seconds:30}") long timeoutSeconds,
            // NOTE: keep internal retries fail-fast by default (0).
            @Value("${llm.high.max-retries:0}") int maxRetries
    ) {
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);

        var builder = OpenAiChatModel.builder()
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        applyMaxRetries(builder, maxRetries);

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

    /**
     * Lightweight LLM-specific circuit breaker used by utility LLM calls
     * (QueryTransformer, query analysis, etc.).
     */
    @Bean
    public NightmareBreaker nightmareBreaker(NightmareBreakerProperties props) {
        return new NightmareBreaker(props);
    }

    /**
     * Backward compatible alias.
     */
    @Bean(name = "localChatModel")
    public ChatModel localChatModel(@Qualifier("miniModel") ChatModel delegate) {
        return delegate;
    }
}