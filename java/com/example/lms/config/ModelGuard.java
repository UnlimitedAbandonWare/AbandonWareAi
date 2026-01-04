package com.example.lms.config;

import com.example.lms.guard.KeyResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Guard bean to ensure that essential LLM configuration properties are present and valid at application startup.
 *
 * <p>Purpose: prevent noisy runtime 4XX such as "model is required" by failing fast.</p>
 */
@EnableConfigurationProperties(ModelGuard.LlmProps.class)
@Component
public class ModelGuard {

    private static final Logger log = LoggerFactory.getLogger(ModelGuard.class);

    private final LlmProps p;
    private final Environment env;
    private final KeyResolver keyResolver;

    public ModelGuard(LlmProps p, Environment env, KeyResolver keyResolver) {
        this.p = p;
        this.env = env;
        this.keyResolver = keyResolver;
    }

    @PostConstruct
    public void verify() {
        // 0) always require chat-model to prevent "model is required"
        if (!StringUtils.hasText(p.chatModel())) {
            throw new IllegalStateException("[LLM] chat-model missing (llm.chat-model)");
        }

        // 1) rgb.moe needs fast endpoint separation (GREEN)
        boolean rgbEnabled = Boolean.parseBoolean(env.getProperty("rgb.moe.enabled", "false"));
        if (rgbEnabled) {
            String fastBase = env.getProperty("llm.fast.base-url");
            if (!StringUtils.hasText(fastBase)) {
                throw new IllegalStateException("[LLM] llm.fast.base-url missing (required when rgb.moe.enabled=true)");
            }
        }

        // 2) keep existing remote sanity checks, but don't over-constrain local provider
        String engine = (p.engine() == null) ? "" : p.engine().trim().toLowerCase();
        if ("llamacpp".equals(engine) || "jlama".equals(engine)) {
            return; // local backend: no remote base-url required
        }

        if (!StringUtils.hasText(p.provider())) {
            throw new IllegalStateException("[LLM] provider missing (llm.provider)");
        }

        // API key is required for non-local providers.
        if (!"local".equalsIgnoreCase(p.provider()) && !StringUtils.hasText(p.apiKey())) {
            throw new IllegalStateException("[LLM] api-key missing (llm.api-key) for provider=" + p.provider());
        }

        if ("openai".equalsIgnoreCase(p.provider())
                && StringUtils.hasText(p.baseUrl())
                && !"https://api.openai.com".equalsIgnoreCase(p.baseUrl())) {
            throw new IllegalStateException("[LLM] OpenAI provider must use https://api.openai.com");
        }

        // 3) BLUE optional: warn if enabled but key missing
        boolean blueEnabled = Boolean.parseBoolean(env.getProperty("rgb.moe.blueEnabled", "true"));
        if (rgbEnabled && blueEnabled) {
            try {
                String k = keyResolver.resolveGeminiApiKeyStrict();
                if (!StringUtils.hasText(k)) {
                    log.warn("[RGB] blueEnabled=true but Gemini key missing; BLUE will be disabled (runner will skip).");
                }
            } catch (Exception e) {
                // strict conflict should fail fast
                throw e;
            }
        }
    }

    @ConfigurationProperties(prefix = "llm")
    public static class LlmProps {
        private String engine;
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String chatModel;

        public String engine() { return engine; }
        public void setEngine(String v) { this.engine = v; }

        public String provider() { return provider; }
        public void setProvider(String v) { this.provider = v; }

        public String baseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }

        public String apiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }

        public String chatModel() { return chatModel; }
        public void setChatModel(String v) { this.chatModel = v; }
    }
}
